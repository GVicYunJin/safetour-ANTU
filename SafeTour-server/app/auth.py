# 登录认证模块：管理员/普通用户验证
import hashlib
from config import ADMIN_USERNAME, ADMIN_PASSWORD
from data_manage import read_json, USERS_JSON_PATH

def md5_encrypt(text):
    """
    MD5加密（简单保护用户密码）
    :param text: 明文
    :return: 加密后的字符串
    """
    return hashlib.md5(text.encode('utf-8')).hexdigest()

def verify_admin(username, password):
    """
    验证管理员账号密码
    :param username: 用户名
    :param password: 密码
    :return: 验证结果（True/False）
    """
    return username == ADMIN_USERNAME and password == ADMIN_PASSWORD

def verify_user(username, password):
    """
    验证普通用户账号密码
    :param username: 用户名
    :param password: 密码
    :return: 验证结果（用户信息/None）
    """
    users = read_json(USERS_JSON_PATH)
    for user in users:
        if user["username"] == username and user["password"] == password:
            return user  # 返回用户信息（含ID、昵称、用户名）
    return None

def get_user_by_id(user_id):
    """
    通过用户ID获取用户信息
    :param user_id: 用户ID
    :return: 用户信息/None
    """
    users = read_json(USERS_JSON_PATH)
    for user in users:
        if user["id"] == user_id:
            return user
    return None