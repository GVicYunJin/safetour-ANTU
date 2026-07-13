# 数据管理核心模块：JSON文件读写、文件夹初始化
import json
import os
import threading
import time
import shutil
import random
import string
from config import USERS_JSON_PATH, TASKS_JSON_PATH, TASKS_BASE_DIR, get_task_photo_dir

# Windows 文件锁支持
try:
    import msvcrt
except ImportError:
    msvcrt = None

# 进程内线程锁（同一进程内多线程同步）
_file_locks = {}
_lock_manager_lock = threading.Lock()

def _get_thread_lock(file_path):
    """
    获取指定文件的线程锁对象（线程安全，同一进程内使用）
    :param file_path: 文件路径
    :return: threading.Lock 对象
    """
    with _lock_manager_lock:
        abs_path = os.path.abspath(file_path)
        if abs_path not in _file_locks:
            _file_locks[abs_path] = threading.Lock()
        return _file_locks[abs_path]

def _gen_temp_filename(file_path):
    """
    生成唯一的临时文件名（包含PID、线程ID、随机字符串，避免冲突）
    :param file_path: 目标文件路径
    :return: 临时文件路径
    """
    dir_name = os.path.dirname(os.path.abspath(file_path))
    base_name = os.path.basename(file_path)
    pid = os.getpid()
    tid = threading.get_ident()
    random_str = ''.join(random.choices(string.ascii_lowercase + string.digits, k=8))
    return os.path.join(dir_name, f".{base_name}.{pid}_{tid}_{random_str}.tmp")

def _atomic_replace(src, dst, max_retries=5, retry_delay=0.1):
    """
    原子替换文件（带重试，解决 Windows 上文件被占用的问题）
    :param src: 源文件（临时文件）
    :param dst: 目标文件
    :param max_retries: 最大重试次数
    :param retry_delay: 重试延迟（秒）
    :return: 成功返回 True，失败返回 False
    """
    for attempt in range(max_retries):
        try:
            os.replace(src, dst)
            return True
        except PermissionError as e:
            # Windows: 文件被其他进程占用，等待后重试
            if attempt < max_retries - 1:
                time.sleep(retry_delay * (2 ** attempt))  # 指数退避
                continue
            print(f"文件替换失败（{dst}）：{e}")
            return False
        except OSError as e:
            # Windows: 错误 32 - 文件正在使用；错误 5 - 拒绝访问
            if e.errno in (32, 5) and attempt < max_retries - 1:
                time.sleep(retry_delay * (2 ** attempt))
                continue
            print(f"文件替换失败（{dst}）：{e}")
            return False
    return False

def init_json_file(file_path, default_content=None):
    """
    初始化JSON文件（不存在则创建）
    :param file_path: 文件路径
    :param default_content: 默认内容（默认空列表）
    """
    default_content = default_content if default_content is not None else []
    lock = _get_thread_lock(file_path)
    with lock:
        if not os.path.exists(file_path):
            # 确保目录存在
            os.makedirs(os.path.dirname(os.path.abspath(file_path)), exist_ok=True)
            # 原子写入：先写临时文件，再替换
            temp_path = _gen_temp_filename(file_path)
            try:
                with open(temp_path, 'w', encoding='utf-8') as f:
                    json.dump(default_content, f, ensure_ascii=False, indent=2)
                    f.flush()
                    os.fsync(f.fileno())
                _atomic_replace(temp_path, file_path)
            except Exception as e:
                print(f"初始化JSON文件失败（{file_path}）：{e}")
                # 清理临时文件
                try:
                    if os.path.exists(temp_path):
                        os.remove(temp_path)
                except:
                    pass

# 初始化用户/任务JSON文件（首次运行自动创建）
try:
    init_json_file(USERS_JSON_PATH, [])
    init_json_file(TASKS_JSON_PATH, [])
except Exception as _e:
    print(f"[data_manage] 警告：初始化 JSON 文件失败：{_e}")

def read_json(file_path):
    """
    读取JSON文件
    :param file_path: 文件路径
    :return: JSON数据（列表/字典）
    """
    # 尝试多次读取（处理写入中的情况）
    for attempt in range(3):
        try:
            with open(file_path, 'r', encoding='utf-8') as f:
                return json.load(f)
        except json.JSONDecodeError as e:
            # JSON格式错误（可能正在写入）
            if attempt < 2:
                time.sleep(0.05 * (attempt + 1))
                continue
            # 最后一次尝试仍失败，尝试备份并重新初始化
            print(f"读取JSON格式错误（{file_path}）：{e}，尝试备份并重新初始化")
            try:
                # 备份损坏的文件
                backup_path = file_path + f".bak.{int(time.time())}"
                if os.path.exists(file_path):
                    shutil.copy2(file_path, backup_path)
                    print(f"损坏文件已备份至：{backup_path}")
                # 重新初始化空列表
                temp_path = _gen_temp_filename(file_path)
                with open(temp_path, 'w', encoding='utf-8') as f:
                    json.dump([], f, ensure_ascii=False, indent=2)
                _atomic_replace(temp_path, file_path)
            except Exception as backup_error:
                print(f"备份/重新初始化失败：{backup_error}")
            return []
        except FileNotFoundError:
            return []
        except PermissionError as e:
            # 文件可能正在写入，等待后重试
            if attempt < 2:
                time.sleep(0.05 * (attempt + 1))
                continue
            print(f"读取JSON被拒绝访问（{file_path}）：{e}")
            return []
        except Exception as e:
            print(f"读取JSON失败（{file_path}）：{e}")
            return []
    return []

def write_json(file_path, data):
    """
    写入JSON文件（原子写入 + 线程锁 + 唯一临时文件名）
    :param file_path: 文件路径
    :param data: 要写入的数据（列表/字典）
    """
    lock = _get_thread_lock(file_path)
    with lock:
        temp_path = _gen_temp_filename(file_path)
        try:
            # 确保目录存在
            os.makedirs(os.path.dirname(os.path.abspath(file_path)), exist_ok=True)
            # 写入临时文件
            with open(temp_path, 'w', encoding='utf-8') as f:
                json.dump(data, f, ensure_ascii=False, indent=2)
                f.flush()
                os.fsync(f.fileno())
            # 原子替换
            success = _atomic_replace(temp_path, file_path)
            if not success:
                # 如果替换失败，尝试用覆盖写入作为最后手段
                with open(file_path, 'w', encoding='utf-8') as f:
                    json.dump(data, f, ensure_ascii=False, indent=2)
        except Exception as e:
            print(f"写入JSON失败（{file_path}）：{e}")
        finally:
            # 确保清理临时文件
            try:
                if os.path.exists(temp_path):
                    os.remove(temp_path)
            except:
                pass

def get_task_dir(task_id):
    """
    获取任务文件夹路径（不存在则创建）
    :param task_id: 任务ID
    :return: 任务文件夹路径
    """
    task_dir = os.path.join(TASKS_BASE_DIR, str(task_id))
    os.makedirs(task_dir, exist_ok=True)
    # 新增：自动创建photo文件夹
    get_task_photo_dir(task_id)
    return task_dir

def get_task_data_path(task_id):
    """
    获取任务数据JSON文件路径（旧格式，兼容使用）
    :param task_id: 任务ID
    :return: 任务数据文件路径
    """
    task_dir = get_task_dir(task_id)
    return os.path.join(task_dir, "task_data.json")

def get_task_data_path_by_uuid(task_id, uuid):
    """
    获取按UUID命名的任务数据JSON文件路径
    :param task_id: 任务ID
    :param uuid: 成员UUID
    :return: 任务数据文件路径
    """
    task_dir = get_task_dir(task_id)
    return os.path.join(task_dir, f"task_data_{uuid}.json")