# 任务定时管理脚本：每分钟自动归档数据
import os
import sys
import time
import datetime

# 添加当前目录到系统路径
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from task_manage import get_all_tasks
from data_manage import read_json, write_json, get_task_data_path_by_uuid, init_json_file


def generate_empty_data():
    """
    生成空数据记录
    :return: 空数据字典
    """
    return {
        "设备本地时间": "",
        "经纬度(东经,北纬)": "",
        "定位精度": "",
        "定位来源": "",
        "MCC": "",
        "MNC": "",
        "LAC": "",
        "CID": "",
        "基站信号强度": "",
        "Wi-Fi 名称 (SSID)": "",
        "Wi-Fi MAC 地址 (BSSID)": "",
        "Wi-Fi 信号强度": "",
        "剩余电量百分比": "",
        "是否充电中": "",
        "GPS是否开启": "",
        "网络类型": "",
        "海拔高度": "",
        "气压": "",
        "速度": "",
        "加速度(X,Y,Z)": "",
        "设备温度": "",
    }


def main():
    """
    主函数：每分钟检查任务状态并生成空数据
    """
#    print("任务定时管理脚本启动...")
    
    while True:
        try:
            # 获取当前时间
            now = datetime.datetime.now()
#            print(f"[{now.strftime('%Y-%m-%d %H:%M:%S')}] 检查时间...")
            
            # 每分钟整时刻执行
            if now.second == 0:
#                print(f"[{now.strftime('%Y-%m-%d %H:%M:%S')}] 执行定时任务...")
                
                try:
                    # 获取所有任务
                    tasks = get_all_tasks()
#                    print(f"  找到 {len(tasks)} 个任务")
                    
                    # 处理每个任务
                    for task in tasks:
                        task_id = task["id"]
                        status = task.get("status", "stopped")
#                        print(f"  任务 {task_id} 状态：{status}")
                        
                        # 只处理运行中的任务
                        if status == "running":
#                            print(f"  处理任务：{task_id}")
                            
                            # 为每个成员生成空数据
                            member_list = task.get("member_list", [])
#                            print(f"  任务 {task_id} 有 {len(member_list)} 个成员")
                            
                            for member in member_list:
                                uuid = member["uuid"]
                                name = member.get("name", "未知")
#                                print(f"    处理成员：{name} ({uuid})")
                                
                                # 生成当前分钟的时间戳
                                current_minute = now.replace(second=0, microsecond=0)
                                time_str = current_minute.strftime("%Y-%m-%d %H:%M:%S")
#                                print(f"    生成时间：{time_str}")
                                
                                # 获取数据文件路径
                                try:
                                    data_path = get_task_data_path_by_uuid(task_id, uuid)
#                                    print(f"    数据文件路径：{data_path}")
                                    
                                    # 初始化文件（如果不存在）
                                    init_json_file(data_path, [])
#                                    print(f"    文件初始化完成")
                                    
                                    # 读取现有数据
                                    task_data = read_json(data_path)
#                                    print(f"    现有数据条数：{len(task_data)}")
                                    
                                    # 生成空数据记录
                                    empty_data = generate_empty_data()
                                    empty_data["设备本地时间"] = time_str
                                    
                                    # 检查是否已存在该分钟的记录
                                    exists = False
                                    for item in task_data:
                                        if item.get("设备本地时间") == time_str:
                                            exists = True
                                            break
                                    
#                                    print(f"    记录是否存在：{exists}")
                                    
                                    # 如果不存在，添加到最上方
                                    if not exists:
                                        task_data.insert(0, empty_data)
                                        write_json(data_path, task_data)
#                                        print(f"    为成员 {uuid} 添加空数据记录")
                                    else:
#                                        print(f"    记录已存在，跳过")
                                        pass
                                except Exception as e:
#                                    print(f"    处理成员 {uuid} 时出错：{e}")
                                    pass
                        else:
#                            print(f"  任务 {task_id} 未运行，跳过")
                            pass
                except Exception as e:
#                    print(f"  处理任务时出错：{e}")
                    pass
                
                # 等待到下一分钟
#                print("  等待到下一分钟...")
                # 计算到下一分钟的秒数
                next_minute = (now.replace(second=0, microsecond=0) + datetime.timedelta(minutes=1))
                wait_seconds = (next_minute - datetime.datetime.now()).total_seconds()
                if wait_seconds > 0:
                    time.sleep(wait_seconds)
            else:
                # 非整分钟，只睡眠1秒
#                print("  非整分钟，等待1秒...")
                time.sleep(1)
        except Exception as e:
#            print(f"脚本运行出错：{e}")
            # 出错后等待10秒继续运行
            time.sleep(10)


if __name__ == "__main__":
    main()