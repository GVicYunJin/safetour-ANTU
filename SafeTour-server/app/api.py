# 客户端数据上传API模块
from flask import Blueprint, request, jsonify
import os
import datetime
from data_manage import read_json, write_json, get_task_data_path, get_task_data_path_by_uuid, init_json_file
from task_manage import get_task_by_uuid, get_task_status
from config import BASE_DIR

# 创建数据API蓝图
api_bp = Blueprint("api", __name__, url_prefix="/api/data-api")

# 创建照片API蓝图
photo_api_bp = Blueprint("photo_api", __name__, url_prefix="/api/photo-api")

@api_bp.route("/upload", methods=["POST"])
def upload_data():
    """
    客户端数据上传接口
    请求格式：JSON（包含UUID、经纬度、设备信息等）
    响应格式：JSON（success: True/False, msg: 提示信息）
    """
    # 检查请求格式
    if request.content_type not in ["application/json", "application/json; charset=utf-8"]:
        return jsonify({"success": False, "msg": "仅支持JSON格式请求"}), 400

    # 解析请求数据
    try:
        data = request.get_json()
    except Exception as e:
        return jsonify({"success": False, "msg": f"JSON解析失败：{str(e)}"}), 400

    # 检查UUID是否存在
    if "UUID" not in data or not data["UUID"]:
        return jsonify({"success": False, "msg": "缺少UUID字段"}), 400

    # 根据UUID查找任务
    task = get_task_by_uuid(data["UUID"])
    if not task:
        return jsonify({"success": False, "msg": f"UUID {data['UUID']} 未关联任何任务"}), 404

    # 检查任务状态
    task_status = get_task_status(task["id"])
    if task_status != "running":
        return jsonify({"success": True, "msg": "任务已停止，数据暂不处理"}), 200

    # 剔除UUID字段，保留业务数据
    business_data = {k: v for k, v in data.items() if k != "UUID"}

    # 处理时间匹配逻辑
    upload_time = business_data.get("设备本地时间")
    if upload_time:
        try:
            # 解析上传时间
            upload_datetime = datetime.datetime.fromisoformat(upload_time.replace("Z", "+00:00"))
            
            # 判断秒数，确定归档分钟
            if upload_datetime.second <= 30:
                # 归到当前分钟
                target_datetime = upload_datetime.replace(second=0, microsecond=0)
            else:
                # 归到下一分钟
                target_datetime = (upload_datetime + datetime.timedelta(minutes=1)).replace(second=0, microsecond=0)
            
            # 更新业务数据的时间为归档时间
            business_data["设备本地时间"] = target_datetime.strftime("%Y-%m-%d %H:%M:%S")
        except Exception as e:
            print(f"时间解析失败：{e}")

    # 追加到对应UUID的数据文件
    task_data_path = get_task_data_path_by_uuid(task["id"], data["UUID"])
    # 初始化文件（如果不存在）
    init_json_file(task_data_path, [])
    task_data = read_json(task_data_path)

    # 检查是否已存在该分钟的记录
    target_time = business_data.get("设备本地时间")
    if target_time:
        record_exists = False
        for i, record in enumerate(task_data):
            if record.get("设备本地时间") == target_time:
                # 覆盖现有记录
                task_data[i] = business_data
                record_exists = True
                break
        
        if not record_exists:
            # 添加到最上方
            task_data.insert(0, business_data)
    else:
        # 没有时间信息，直接添加到最上方
        task_data.insert(0, business_data)

    write_json(task_data_path, task_data)

    return jsonify({"success": True, "msg": "数据上传成功"}), 200

@photo_api_bp.route("/upload", methods=["POST"])
def upload_photo():
    """
    客户端照片上传接口
    请求格式：multipart/form-data（包含UUID和照片文件）
    响应格式：JSON（success: True/False, msg: 提示信息）
    """
    # 检查请求格式
    if 'file' not in request.files:
        return jsonify({"success": False, "msg": "缺少文件字段"}), 400

    # 检查UUID是否存在
    uuid = request.form.get('UUID')
    if not uuid:
        return jsonify({"success": False, "msg": "缺少UUID字段"}), 400

    # 根据UUID查找任务
    task = get_task_by_uuid(uuid)
    if not task:
        return jsonify({"success": False, "msg": f"UUID {uuid} 未关联任何任务"}), 404

    # 获取文件
    file = request.files['file']
    if file.filename == '':
        return jsonify({"success": False, "msg": "未选择文件"}), 400

    # 确保任务照片目录存在
    task_photo_dir = os.path.join(BASE_DIR, "tasks", task["id"], "photos")
    # 按UUID创建子文件夹
    user_photo_dir = os.path.join(task_photo_dir, uuid)
    os.makedirs(user_photo_dir, exist_ok=True)

    # 生成唯一文件名：UUID-上传时间（精确到秒）
    import datetime
    timestamp = datetime.datetime.now().strftime("%Y%m%d%H%M%S")
    ext = os.path.splitext(file.filename)[1]
    filename = f"{uuid}-{timestamp}{ext}"
    file_path = os.path.join(user_photo_dir, filename)

    # 保存文件
    try:
        file.save(file_path)
        print(f"[QQ推送-触发] 照片保存成功: {file_path}, task_id={task['id']}")
        
        # ========== QQ推送：保存成功后异步发送到QQ群（无论任务状态都推送）==========
        try:
            from qq_push import get_qq_push_config, send_image_to_qq
            import threading
            import time
            
            qq_config = get_qq_push_config()
            enabled_flag = qq_config.get("enabled", False)
            print(f"[QQ推送-触发] 读取配置 enabled={enabled_flag} (type={type(enabled_flag).__name__}), api_url={qq_config.get('api_url', '')}")
            
            if enabled_flag:
                # 获取成员姓名作为说明
                member_name = uuid
                for member in task.get("member_list", []):
                    if member.get("uuid") == uuid:
                        member_name = member.get("name", uuid)
                        break
                
                # 构建说明文字
                caption = f"【{task['id']}】成员: {member_name} (UUID: {uuid})\n时间: {datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')}"
                print(f"[QQ推送-触发] caption={caption}")
                
                # 异步发送（使用非守护线程，确保有机会执行；不 join 避免阻塞响应）
                def _send_to_qq(_file_path=file_path, _task_id=task["id"], _caption=caption):
                    try:
                        print(f"[QQ推送-线程] 线程已启动，准备调用 send_image_to_qq")
                        success, msg = send_image_to_qq(_file_path, _task_id, _caption)
                        print(f"[QQ推送-线程] 推送结果: {'成功' if success else '失败'} - {msg}")
                    except Exception as ex:
                        print(f"[QQ推送-线程] 推送异常: {ex}")
                
                thread = threading.Thread(target=_send_to_qq, daemon=False)
                thread.start()
                # 极小延迟确保线程启动（不影响响应速度）
                time.sleep(0.01)
                print(f"[QQ推送-触发] 推送线程已启动，thread_alive={thread.is_alive()}")
            else:
                print(f"[QQ推送-触发] 推送未启用（enabled={enabled_flag}），已跳过")
        except Exception as push_error:
            print(f"[QQ推送-触发] 推送初始化失败（不影响文件保存）: {push_error}")
            import traceback
            traceback.print_exc()
        # ============================================================
        
        return jsonify({"success": True, "msg": "照片上传成功"}), 200
    except Exception as e:
        return jsonify({"success": False, "msg": f"文件保存失败：{str(e)}"}), 500

@photo_api_bp.route("/list", methods=["GET"])
def list_photos():
    """
    获取指定任务和UUID的图片列表
    请求参数：task_id, uuid
    响应格式：JSON（success: True/False, data: 图片列表）
    """
    task_id = request.args.get('task_id')
    uuid = request.args.get('uuid')
    
    if not task_id or not uuid:
        return jsonify({"success": False, "msg": "缺少参数"}), 400
    
    # 构建图片目录路径
    photo_dir = os.path.join(BASE_DIR, "tasks", task_id, "photos", uuid)
    
    # 检查目录是否存在
    if not os.path.exists(photo_dir):
        return jsonify({"success": True, "data": []}), 200
    
    # 获取目录中的所有图片文件
    photos = []
    for filename in os.listdir(photo_dir):
        if filename.endswith(('.jpg', '.jpeg', '.png', '.gif')):
            photos.append({
                "filename": filename,
                "url": f"/tasks/{task_id}/photos/{uuid}/{filename}"
            })
    
    # 按文件名排序（文件名包含时间戳，所以可以直接排序）
    photos.sort(key=lambda x: x["filename"], reverse=True)
    
    return jsonify({"success": True, "data": photos}), 200