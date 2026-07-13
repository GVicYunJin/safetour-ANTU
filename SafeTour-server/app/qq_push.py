# QQ推送管理模块：配置读写、图片推送
import base64
import json
import os
import requests
import threading

# 先导入 config（被 data_manage 间接引用），确保模块加载顺序稳定
try:
    from config import QQ_PUSH_CONFIG_PATH, BASE_DIR
    print(f"[qq_push] 成功从 config 导入: QQ_PUSH_CONFIG_PATH={QQ_PUSH_CONFIG_PATH}", flush=True)
except ImportError as e:
    # 兜底：如果 config 导入失败，自行构造路径
    BASE_DIR = os.path.dirname(os.path.abspath(__file__))
    QQ_PUSH_CONFIG_PATH = os.path.join(BASE_DIR, "qq_push_config.json")
    print(f"[qq_push] 警告：从 config 导入失败（{e}），已回退到本地路径 {QQ_PUSH_CONFIG_PATH}", flush=True)

from data_manage import init_json_file, read_json, write_json

# 线程锁
_lock = threading.Lock()

# 默认配置
DEFAULT_CONFIG = {
    "enabled": False,
    "api_url": "",
    "token": "",
    "default_group": "",
    "task_groups": {}  # {task_id: group_id}
}


def get_qq_push_config():
    """
    获取QQ推送配置
    :return: 配置字典
    """
    with _lock:
        # 初始化配置文件
        if not os.path.exists(QQ_PUSH_CONFIG_PATH):
            init_json_file(QQ_PUSH_CONFIG_PATH, DEFAULT_CONFIG)
            return dict(DEFAULT_CONFIG)
        
        config = read_json(QQ_PUSH_CONFIG_PATH)
        # 确保所有字段存在
        for key, value in DEFAULT_CONFIG.items():
            if key not in config:
                config[key] = value
        # 标准化 enabled 字段为布尔值（兼容配置文件中可能出现的字符串 "true"/"false"/"on"/"off"/"1"/"0"）
        raw_enabled = config.get("enabled", False)
        if isinstance(raw_enabled, bool):
            config["enabled"] = raw_enabled
        elif isinstance(raw_enabled, str):
            config["enabled"] = raw_enabled.strip().lower() in ("true", "on", "1", "yes")
        else:
            config["enabled"] = bool(raw_enabled)
        return config


def save_qq_push_config(config):
    """
    保存QQ推送配置
    :param config: 配置字典
    :return: True/False
    """
    with _lock:
        try:
            write_json(QQ_PUSH_CONFIG_PATH, config)
            return True
        except Exception as e:
            print(f"保存QQ推送配置失败：{e}")
            return False


def update_qq_push_config(enabled=None, api_url=None, token=None, default_group=None, task_groups=None):
    """
    更新QQ推送配置（部分字段）
    :param enabled: 是否启用
    :param api_url: API接口地址
    :param token: Token
    :param default_group: 默认群号
    :param task_groups: 任务群号映射 {task_id: group_id}
    :return: True/False
    """
    config = get_qq_push_config()
    
    if enabled is not None:
        config["enabled"] = enabled
    if api_url is not None:
        config["api_url"] = api_url
    if token is not None:
        config["token"] = token
    if default_group is not None:
        config["default_group"] = default_group
    if task_groups is not None:
        config["task_groups"] = task_groups
    
    return save_qq_push_config(config)


def get_group_for_task(task_id):
    """
    获取指定任务的群号
    :param task_id: 任务ID
    :return: 群号（优先使用任务配置，否则使用默认配置，都没有则取 task_groups 中的第一个）
    """
    config = get_qq_push_config()
    task_groups = config.get("task_groups", {})
    # 1. 优先使用任务专属配置
    if task_id in task_groups and task_groups[task_id]:
        return task_groups[task_id]
    # 2. 其次使用默认群号
    default_group = config.get("default_group", "")
    if default_group:
        return default_group
    # 3. 默认群号也为空时，从 task_groups 中取第一个可用群号作为兜底
    for _, gid in task_groups.items():
        if gid:
            return gid
    return ""


def send_image_to_qq(image_path, task_id, caption=""):
    """
    发送图片到QQ群（最简版，结构与独立测试脚本一致）
    :param image_path: 图片本地路径
    :param task_id: 任务ID（用于读取该任务对应的群号）
    :param caption: 图片说明文字（可为空）
    :return: (success: bool, message: str)
    """
    print(f"[QQ推送] 开始发送, task_id={task_id}, image_path={image_path}", flush=True)

    # ========== 读取配置 ==========
    config = get_qq_push_config()
    if not config.get("enabled", False):
        print(f"[QQ推送] ❌ 推送未启用（enabled={config.get('enabled')}）", flush=True)
        return (False, "QQ推送未启用")

    NAPCAT_URL = config.get("api_url", "").strip()
    TOKEN = config.get("token", "").strip()
    GROUP_ID = get_group_for_task(task_id)

    print(f"[QQ推送] 配置: api_url={NAPCAT_URL}, group_id={GROUP_ID}, "
          f"token={'(已配置)' if TOKEN else '(空)'}", flush=True)

    if not NAPCAT_URL:
        print(f"[QQ推送] ❌ api_url 为空", flush=True)
        return (False, "API接口地址未配置")
    if not GROUP_ID:
        print(f"[QQ推送] ❌ 任务 {task_id} 未配置群号且无默认群号", flush=True)
        return (False, f"任务 {task_id} 未配置群号且无默认群号")
    if not os.path.exists(image_path):
        print(f"[QQ推送] ❌ 图片不存在: {image_path}", flush=True)
        return (False, f"图片文件不存在：{image_path}")

    # ========== URL：严格使用管理员界面输入的地址，不做任何自动拼装 ==========
    url = NAPCAT_URL.strip()

    # ========== 群号类型转换 ==========
    try:
        group_id_int = int(GROUP_ID)
    except (ValueError, TypeError):
        print(f"[QQ推送] ❌ 群号不是有效数字: {GROUP_ID}", flush=True)
        return (False, f"群号不是有效数字：{GROUP_ID}")

    # ========== 请求头 ==========
    headers = {
        "Content-Type": "application/json"
    }
    if TOKEN:
        headers["Authorization"] = f"Bearer {TOKEN}"

    # ========== 读取图片并转 base64 ==========
    with open(image_path, "rb") as f:
        img_bytes = f.read()
        img_base64 = base64.b64encode(img_bytes).decode("utf-8")

    # ========== 构造消息体：文字说明 + 图片 ==========
    message = []
    if caption:
        message.append({"type": "text", "data": {"text": caption}})
    message.append({"type": "image", "data": {"file": f"base64://{img_base64}"}})

    msg_data = {
        "group_id": group_id_int,
        "message": message
    }

    # ========== 发送 ==========
    try:
        print(f"[QQ推送] 🚀 POST {url} (group_id={group_id_int}, base64={len(img_base64)}字符)", flush=True)
        resp = requests.post(
            url=url,
            headers=headers,
            data=json.dumps(msg_data),
            timeout=20
        )
        print(f"[QQ推送] 状态码: {resp.status_code}, 响应: {resp.text[:300]}", flush=True)

        # 判断是否成功（OneBot 标准 retcode == 0 即成功）
        if resp.status_code == 200:
            try:
                result = resp.json()
                if result.get("retcode") == 0 or result.get("status") == "ok":
                    return (True, "图片发送成功")
                return (False, f"API返回错误: {result.get('msg', result.get('message', resp.text))}")
            except Exception:
                return (True, "图片发送成功（响应非JSON）")
        else:
            return (False, f"HTTP请求失败，状态码：{resp.status_code}")

    except Exception as e:
        print(f"[QQ推送] ❌ 发送异常: {e}", flush=True)
        return (False, f"发送失败：{str(e)}")