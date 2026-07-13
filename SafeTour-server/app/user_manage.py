# 用户管理模块：增删查
import uuid
from data_manage import read_json, write_json, USERS_JSON_PATH

def get_all_users():
    """获取所有用户列表"""
    return read_json(USERS_JSON_PATH)

def add_user(nickname, username, password):
    """
    添加新用户（修改：存储明文密码，便于展示）
    :param nickname: 昵称
    :param username: 用户名
    :param password: 密码（明文存储）
    :return: 添加结果（True/False）
    """
    users = get_all_users()
    # 检查用户名是否重复
    for user in users:
        if user["username"] == username:
            return False
    # 生成用户ID（UUID简化）
    user_id = str(uuid.uuid4()).replace("-", "")[:8]
    # 修改：直接存储明文密码，不加密
    new_user = {
        "id": user_id,
        "nickname": nickname,
        "username": username,
        "password": password  # 明文存储
    }
    users.append(new_user)
    write_json(USERS_JSON_PATH, users)
    return True

def delete_user(user_id):
    """
    删除用户
    :param user_id: 用户ID
    :return: 删除结果（True/False）
    """
    users = get_all_users()
    new_users = [u for u in users if u["id"] != user_id]
    if len(new_users) == len(users):
        return False  # 未找到用户
    write_json(USERS_JSON_PATH, new_users)
    return True

def update_user_password(user_id, old_password, new_password):
    """
    修改用户密码
    :param user_id: 用户ID
    :param old_password: 原密码
    :param new_password: 新密码
    :return: 修改结果（True/False）
    """
    users = get_all_users()
    for i, user in enumerate(users):
        if user["id"] == user_id:
            # 验证原密码
            if user["password"] == old_password:
                # 更新密码
                users[i]["password"] = new_password
                write_json(USERS_JSON_PATH, users)
                return True
            else:
                return False  # 原密码错误
    return False  # 用户不存在