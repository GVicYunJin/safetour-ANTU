# 系统配置文件（可自行修改管理员账号、路径等）
import os

# ==================== 管理员配置 ====================
ADMIN_USERNAME = "admin"  # 管理员账号（可修改）
ADMIN_PASSWORD = "admin"  # 管理员密码（可修改）

# ==================== 路径配置 ====================
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
# 用户信息存储路径
USERS_JSON_PATH = os.path.join(BASE_DIR, "users.json")
# 任务信息存储路径
TASKS_JSON_PATH = os.path.join(BASE_DIR, "tasks.json")
# 任务数据存储根目录
TASKS_BASE_DIR = os.path.join(BASE_DIR, "tasks")

# ==================== 位置配置 ====================
# 默认位置坐标（东经,北纬）- 修改此处即可更改默认位置
DEFAULT_LOCATION = "116.397029,39.917839"

# ==================== 其他配置 ====================
# 允许的请求格式
ALLOWED_CONTENT_TYPES = {"application/json"}
# UUID长度（固定12位）
UUID_LENGTH = 12

# QQ推送配置文件路径
QQ_PUSH_CONFIG_PATH = os.path.join(BASE_DIR, "qq_push_config.json")

# 确保基础目录存在（包裹在 try/except 中，避免 Docker 权限问题导致整个模块加载失败）
try:
    os.makedirs(TASKS_BASE_DIR, exist_ok=True)
except Exception as _e:
    print(f"[config] 警告：无法创建基础目录 {TASKS_BASE_DIR}：{_e}")

# ==================== 新增：Photo配置 ====================
def get_task_photo_dir(task_id):
    """获取任务图片存储目录"""
    photo_dir = os.path.join(TASKS_BASE_DIR, str(task_id), "photo")
    os.makedirs(photo_dir, exist_ok=True)
    return photo_dir