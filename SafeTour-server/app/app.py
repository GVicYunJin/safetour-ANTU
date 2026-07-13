# 主应用入口：路由注册、服务启动
from flask import Flask, render_template, request, jsonify, session, redirect, url_for, send_file, Response
import os
import threading
from config import BASE_DIR
from auth import verify_admin, verify_user, get_user_by_id
from user_manage import get_all_users, add_user, delete_user, update_user_password
from task_manage import (
    get_all_tasks, create_task, delete_task,
    assign_task_permission, get_user_authorized_tasks, get_task_data,
    update_task_status
)
from api import api_bp, photo_api_bp
from qq_push import get_qq_push_config, save_qq_push_config

# 创建Flask应用
app = Flask(__name__, 
            template_folder=os.path.join(BASE_DIR, "templates"),
            static_folder=os.path.join(BASE_DIR, "tasks"),
            static_url_path="/tasks")
# 会话密钥（必须设置，用于保存登录状态）
app.secret_key = "safetour_key"  # 可自行修改

# 注册API蓝图
app.register_blueprint(api_bp)
app.register_blueprint(photo_api_bp)

# ==================== 管理员端路由 ====================
@app.route("/admin/login", methods=["GET", "POST"])
def admin_login():
    """管理员登录"""
    if request.method == "POST":
        username = request.form.get("username")
        password = request.form.get("password")
        if verify_admin(username, password):
            # 保存管理员登录状态
            session["admin_logged"] = True
            return redirect(url_for("admin_index"))
        return render_template("admin/login.html", error="账号或密码错误")
    # GET请求：已登录则跳转到首页
    if session.get("admin_logged"):
        return redirect(url_for("admin_index"))
    return render_template("admin/login.html")

@app.route("/admin/index")
def admin_index():
    """管理员首页"""
    if not session.get("admin_logged"):
        return redirect(url_for("admin_login"))
    return render_template("admin/index.html")

@app.route("/admin/user_manage", methods=["GET", "POST"])
def admin_user_manage():
    """用户管理"""
    if not session.get("admin_logged"):
        return redirect(url_for("admin_login"))
    
    if request.method == "POST":
        # 添加用户
        nickname = request.form.get("nickname")
        username = request.form.get("username")
        password = request.form.get("password")
        if add_user(nickname, username, password):
            return jsonify({"success": True, "msg": "用户添加成功"})
        return jsonify({"success": False, "msg": "用户名已存在"})
    
    # GET请求：返回用户列表
    users = get_all_users()
    return render_template("admin/user_manage.html", users=users)

@app.route("/admin/user_delete", methods=["POST"])
def admin_user_delete():
    """删除用户"""
    if not session.get("admin_logged"):
        return jsonify({"success": False, "msg": "未登录"}), 401
    user_id = request.form.get("user_id")
    if delete_user(user_id):
        return jsonify({"success": True, "msg": "用户删除成功"})
    return jsonify({"success": False, "msg": "用户不存在"})

@app.route("/admin/task_create", methods=["GET", "POST"])
def admin_task_create():
    """创建任务（修改：接收成员姓名列表）"""
    if not session.get("admin_logged"):
        return redirect(url_for("admin_login"))
    
    if request.method == "POST":
        # 创建任务
        task_id = request.form.get("task_id")
        participant_count = request.form.get("participant_count")
        # 新增：获取成员姓名列表
        participant_names = request.form.getlist("participant_names[]")
        task_id = create_task(task_id, participant_count, participant_names)
        if task_id:
            return jsonify({"success": True, "msg": f"任务创建成功（ID：{task_id}）"})
        return jsonify({"success": False, "msg": "任务ID不能为空或已存在，参与人数必须是正整数且姓名不能为空"})
    
    # GET请求：返回创建页面
    return render_template("admin/task_create.html")

@app.route("/admin/task_auth", methods=["GET", "POST"])
def admin_task_auth():
    """任务权限分配"""
    if not session.get("admin_logged"):
        return redirect(url_for("admin_login"))
    
    if request.method == "POST":
        # 分配权限
        task_id = request.form.get("task_id")
        user_ids = request.form.getlist("user_ids[]")  # 多选用户ID
        if assign_task_permission(task_id, user_ids):
            return jsonify({"success": True, "msg": "权限分配成功"})
        return jsonify({"success": False, "msg": "任务不存在"})
    
    # GET请求：返回权限分配页面（任务列表+用户列表）
    tasks = get_all_tasks()
    users = get_all_users()
    return render_template("admin/task_auth.html", tasks=tasks, users=users)

@app.route("/admin/task_manage", methods=["GET", "POST"])
def admin_task_manage():
    """任务管理"""
    if not session.get("admin_logged"):
        return redirect(url_for("admin_login"))
    
    if request.method == "POST":
        # 删除任务
        task_id = request.form.get("task_id")
        if delete_task(task_id):
            return jsonify({"success": True, "msg": "任务删除成功"})
        return jsonify({"success": False, "msg": "任务不存在"})
    
    # GET请求：返回任务列表
    tasks = get_all_tasks()
    return render_template("admin/task_manage.html", tasks=tasks)

@app.route("/admin/task_status", methods=["POST"])
def admin_task_status():
    """任务状态更新"""
    if not session.get("admin_logged"):
        return jsonify({"success": False, "msg": "未登录"}), 401
    task_id = request.form.get("task_id")
    status = request.form.get("status")
    if update_task_status(task_id, status):
        return jsonify({"success": True, "msg": "任务状态更新成功"})
    return jsonify({"success": False, "msg": "任务不存在"})

@app.route("/admin/qq_push", methods=["GET", "POST"])
def admin_qq_push():
    """QQ推送管理"""
    if not session.get("admin_logged"):
        return redirect(url_for("admin_login"))
    
    if request.method == "POST":
        # 保存配置
        try:
            # 获取表单数据
            enabled = request.form.get("enabled") == "on" or request.form.get("enabled") == "true"
            api_url = request.form.get("api_url", "").strip()
            token = request.form.get("token", "").strip()
            default_group = request.form.get("default_group", "").strip()
            
            # 获取任务群号配置
            task_groups = {}
            from task_manage import get_all_tasks
            tasks = get_all_tasks()
            for task in tasks:
                task_id = task["id"]
                group = request.form.get(f"task_group_{task_id}", "").strip()
                if group:
                    task_groups[task_id] = group
            
            # 构建新配置
            new_config = {
                "enabled": enabled,
                "api_url": api_url,
                "token": token,
                "default_group": default_group,
                "task_groups": task_groups
            }
            
            if save_qq_push_config(new_config):
                return jsonify({"success": True, "msg": "QQ推送配置保存成功"})
            return jsonify({"success": False, "msg": "配置保存失败"})
        except Exception as e:
            return jsonify({"success": False, "msg": f"保存异常：{str(e)}"}), 500
    
    # GET请求：返回配置页面
    from task_manage import get_all_tasks
    config = get_qq_push_config()
    tasks = get_all_tasks()
    return render_template("admin/qq_push.html", config=config, tasks=tasks)


@app.route("/admin/about")
def admin_about():
    """关于页面"""
    if not session.get("admin_logged"):
        return redirect(url_for("admin_login"))
    return render_template("admin/about.html")


@app.route("/admin/logout")
def admin_logout():
    """管理员退出登录"""
    session.pop("admin_logged", None)
    return redirect(url_for("admin_login"))

# ==================== 用户端路由 ====================
@app.route("/user/login", methods=["GET", "POST"])
def user_login():
    """普通用户登录"""
    if request.method == "POST":
        username = request.form.get("username")
        password = request.form.get("password")
        user = verify_user(username, password)
        if user:
            # 保存用户登录状态
            session["user_logged"] = True
            session["user_id"] = user["id"]
            session["user_nickname"] = user["nickname"]
            return redirect(url_for("user_menu"))
        return render_template("user/login.html", error="账号或密码错误")
    # GET请求：已登录则跳转到菜单页面
    if session.get("user_logged"):
        return redirect(url_for("user_menu"))
    return render_template("user/login.html")

@app.route("/user/menu")
def user_menu():
    """用户菜单页面"""
    if not session.get("user_logged"):
        return redirect(url_for("user_login"))
    # 获取用户授权的任务
    user_id = session.get("user_id")
    authorized_tasks = get_user_authorized_tasks(user_id)
    return render_template("user/menu.html", 
                           nickname=session.get("user_nickname"),
                           authorized_tasks=authorized_tasks)

@app.route("/user/change_password", methods=["GET", "POST"])
def user_change_password():
    """用户修改密码"""
    if not session.get("user_logged"):
        return redirect(url_for("user_login"))
    
    if request.method == "POST":
        old_password = request.form.get("old_password")
        new_password = request.form.get("new_password")
        user_id = session.get("user_id")
        
        if update_user_password(user_id, old_password, new_password):
            return jsonify({"success": True, "msg": "密码修改成功"})
        return jsonify({"success": False, "msg": "原密码错误"})
    
    return render_template("user/change_password.html")

@app.route("/user/task_data/<path:task_id>")
def user_task_data(task_id):
    """任务数据页面"""
    if not session.get("user_logged"):
        return redirect(url_for("user_login"))
    # 验证用户是否有权限
    user_id = session.get("user_id")
    authorized_tasks = get_user_authorized_tasks(user_id)
    if any(t["id"] == task_id for t in authorized_tasks):
        return render_template("user/task_data.html", task_id=task_id)
    return redirect(url_for("user_menu"))

@app.route("/user/get_task_data", methods=["POST"])
def user_get_task_data():
    """获取任务数据"""
    if not session.get("user_logged"):
        return jsonify({"success": False, "msg": "未登录"}), 401
    task_id = request.form.get("task_id")
    # 验证用户是否有权限
    user_id = session.get("user_id")
    authorized_tasks = get_user_authorized_tasks(user_id)
    if any(t["id"] == task_id for t in authorized_tasks):
        task_data = get_task_data(task_id)
        # 获取任务信息
        task_info = next((t for t in authorized_tasks if t["id"] == task_id), None)
        return jsonify({"success": True, "data": task_data, "taskInfo": task_info})
    return jsonify({"success": False, "msg": "无权限访问该任务"})

@app.route("/user/logout")
def user_logout():
    """用户退出登录"""
    session.pop("user_logged", None)
    session.pop("user_id", None)
    session.pop("user_nickname", None)
    return redirect(url_for("user_login"))

# ==================== 通用路由 ====================
@app.route("/")
def index():
    """首页重定向到管理员登录"""
    return redirect(url_for("admin_login"))

@app.route("/logo.png")
def get_logo():
    """返回Logo图片"""
    logo_path = os.path.join(BASE_DIR, "logo", "logo.png")
    if os.path.exists(logo_path):
        return send_file(logo_path, mimetype="image/png")
    # 默认SVG logo（渐变紫-靛蓝色圆形+字母A）
    svg = '<?xml version="1.0" encoding="UTF-8"?><svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><defs><linearGradient id="g" x1="0%" y1="0%" x2="100%" y2="100%"><stop offset="0%" stop-color="#6366f1"/><stop offset="100%" stop-color="#a855f7"/></linearGradient></defs><circle cx="32" cy="32" r="30" fill="url(#g)"/><text x="50%" y="58%" text-anchor="middle" fill="white" font-family="Arial,sans-serif" font-size="20" font-weight="bold">A</text></svg>'
    return Response(svg, mimetype="image/svg+xml")

# 启动任务定时管理脚本
def start_task_timer():
    """
    在后台启动任务定时管理脚本
    """
    try:
        import task_timer
        print("启动任务定时管理脚本...")
        # 创建并启动线程
        timer_thread = threading.Thread(target=task_timer.main, daemon=True)
        timer_thread.start()
        print("任务定时管理脚本启动成功")
    except Exception as e:
        print(f"启动任务定时管理脚本失败：{e}")

# ==================== 启动服务 ====================
if __name__ == "__main__":
    # 检测 Flask debug 模式的 reloader 进程，只在主进程启动定时器
    # Flask debug 模式会通过 WERKZEUG_RUN_MAIN 环境变量识别子进程
    import os
    if os.environ.get('WERKZEUG_RUN_MAIN') != 'true':
        # 主进程启动时才运行一次初始化逻辑
        pass
    
    # 只在真正的运行进程中启动定时器（避免 debug 模式双进程导致双重写入）
    if os.environ.get('WERKZEUG_RUN_MAIN') == 'true' or not app.debug:
        start_task_timer()
    else:
        print("主进程启动中，等待 reloader 进程启动后再启动定时器...")
    
    # 输出欢迎信息
    print("\nSafeTour-安途 服务端已成功启动！")
    print("\n若是云服务器请先在安全组放行端口。")
    print("\n管理员登录：https://[公网IP]:21121/admin/login")
    print("用户登录：https://[公网IP]:21121/user/login")
    print("Data API：https://[公网IP]:21121/api/data-api/upload")
    print("Photo API：https://[公网IP]:21121/api/photo-api/upload")
    print("UUID请在创建任务后查看。")
    print("\n管理员密码请在/app/config.py中修改，修改后需重新启动。\n")
    print("\n有任何问题请向仓库反馈。开源仓库地址：https://github.com/GVicYunJin/safetour-ANTU")
    print("本项目开源免费，如果你有偿获得，请收集凭证并提交给我们2582634359@qq.com，我们会依法追究责任\n")
    print("\n欢迎使用 安途-SafeTour ！\n")
    print("\n\n")
    print("\n\n")

    
    # 启动Flask服务（关闭 debug reloader，避免含空格路径中子进程找不到 __main__）
    app.run(host="0.0.0.0", port=21121, debug=False, use_reloader=False)