# 任务管理模块：创建、删除、权限分配、UUID生成
import uuid
import os
import shutil
from data_manage import read_json, write_json, TASKS_JSON_PATH, get_task_dir, get_task_data_path, get_task_data_path_by_uuid, init_json_file
from config import get_task_photo_dir

def generate_uuid(length=12):
    """
    生成指定长度的UUID（大小写字母+数字）
    :param length: 长度（默认12）
    :return: 12位UUID字符串
    """
    # 生成UUID并替换特殊字符，转为大小写混合
    raw_uuid = str(uuid.uuid4()).replace("-", "")
    # 混合大小写 + 数字
    uuid_chars = []
    for i, c in enumerate(raw_uuid):
        if i % 2 == 0 and c.isalpha():
            uuid_chars.append(c.upper())
        else:
            uuid_chars.append(c.lower())
    # 截取指定长度
    return ''.join(uuid_chars)[:length]

def get_all_tasks():
    """获取所有任务列表"""
    return read_json(TASKS_JSON_PATH)

def create_task(task_id, participant_count, participant_names):
    """
    创建新任务（修改：接收手动输入的任务ID，支持英文、数字、汉字）
    :param task_id: 任务ID（手动输入）
    :param participant_count: 参与人数
    :param participant_names: 成员姓名列表
    :return: 任务ID/None（失败）
    """
    try:
        participant_count = int(participant_count)
        if participant_count <= 0 or len(participant_names) != participant_count:
            return None
    except ValueError:
        return None

    # 验证任务ID不为空
    if not task_id or not task_id.strip():
        return None

    tasks = get_all_tasks()
    # 检查任务ID是否已存在
    for task in tasks:
        if task["id"] == task_id:
            return None
    # 生成UUID列表
    uuid_list = [generate_uuid() for _ in range(participant_count)]
    # 新增：构建成员列表（UUID-姓名映射）
    member_list = []
    for i in range(participant_count):
        member_list.append({
            "uuid": uuid_list[i],
            "name": participant_names[i].strip() or f"成员{i+1}"  # 空姓名默认填充
        })
    # 任务信息（修改：使用手动输入的task_id）
    new_task = {
        "id": task_id,
        "participant_count": participant_count,
        "uuid_list": uuid_list,
        "member_list": member_list,  # 新增：UUID-姓名映射
        "authorized_users": [],  # 授权用户ID列表
        "status": "stopped"  # 任务状态：running/stopped
    }
    # 添加任务
    tasks.append(new_task)
    write_json(TASKS_JSON_PATH, tasks)
    # 创建任务文件夹 + 初始化数据文件
    task_data_path = get_task_data_path(task_id)
    init_json_file(task_data_path, [])
    return task_id

def delete_task(task_id):
    """
    删除任务（修改：同时删除photo文件夹）
    :param task_id: 任务ID
    :return: 删除结果（True/False）
    """
    # 删除任务信息
    tasks = get_all_tasks()
    new_tasks = [t for t in tasks if t["id"] != task_id]
    if len(new_tasks) == len(tasks):
        return False
    write_json(TASKS_JSON_PATH, new_tasks)
    # 删除任务文件夹（包含photo）
    task_dir = get_task_dir(task_id)
    if os.path.exists(task_dir):
        shutil.rmtree(task_dir)
    return True

def get_task_by_uuid(uuid_str):
    """
    通过UUID查找对应任务
    :param uuid_str: UUID字符串
    :return: 任务信息/None
    """
    tasks = get_all_tasks()
    for task in tasks:
        if uuid_str in task["uuid_list"]:
            return task
    return None

def assign_task_permission(task_id, user_ids):
    """
    分配任务权限（指定哪些用户可访问）
    :param task_id: 任务ID
    :param user_ids: 用户ID列表
    :return: 分配结果（True/False）
    """
    tasks = get_all_tasks()
    for i, task in enumerate(tasks):
        if task["id"] == task_id:
            tasks[i]["authorized_users"] = user_ids
            write_json(TASKS_JSON_PATH, tasks)
            return True
    return False

def update_task_status(task_id, status):
    """
    更新任务状态
    :param task_id: 任务ID
    :param status: 状态：running/stopped
    :return: 更新结果（True/False）
    """
    tasks = get_all_tasks()
    for i, task in enumerate(tasks):
        if task["id"] == task_id:
            tasks[i]["status"] = status
            write_json(TASKS_JSON_PATH, tasks)
            return True
    return False

def get_task_status(task_id):
    """
    获取任务状态
    :param task_id: 任务ID
    :return: 状态：running/stopped
    """
    tasks = get_all_tasks()
    for task in tasks:
        if task["id"] == task_id:
            return task.get("status", "stopped")
    return "stopped"

def get_user_authorized_tasks(user_id):
    """
    获取用户授权的所有任务
    :param user_id: 用户ID
    :return: 任务列表
    """
    tasks = get_all_tasks()
    authorized_tasks = []
    for task in tasks:
        if user_id in task["authorized_users"]:
            authorized_tasks.append(task)
    return authorized_tasks

def get_task_data(task_id):
    """
    获取任务的JSON数据（按成员组织）
    :param task_id: 任务ID
    :return: 按成员组织的任务数据（字典）
    """
    # 获取任务信息
    tasks = get_all_tasks()
    task_info = next((t for t in tasks if t["id"] == task_id), None)
    if not task_info:
        return {}
    
    # 按成员组织数据
    member_data = {}
    for member in task_info.get("member_list", []):
        uuid = member["uuid"]
        name = member["name"]
        # 读取对应UUID的数据文件
        data_path = get_task_data_path_by_uuid(task_id, uuid)
        member_data[uuid] = {
            "name": name,
            "data": read_json(data_path)
        }
    
    return member_data