import os
import sys
import subprocess

def main():
    current_dir = os.path.dirname(os.path.abspath(__file__))
    eula_file = os.path.join(current_dir, "eula.txt")
    app_entry = os.path.join(current_dir, "app", "app.py")

    print("==================== 启动加载器 ====================")

    if not os.path.exists(eula_file):
        print("未找到 eula.txt，自动创建协议文件")
        with open(eula_file, "w", encoding="utf-8") as f:
            f.write("eula=false\n")
        print("\n请打开 eula.txt，将第一行改为 eula=true 同意协议！")
        input("按回车退出...")
        return

    with open(eula_file, "r", encoding="utf-8") as f:
        first_line = f.readline().strip().lower()

    if first_line.startswith("eula="):
        eula_status = first_line.split("=", 1)[1].strip()
    else:
        eula_status = "false"

    if eula_status != "true":
        print("\n❌ eula=false：尚未同意用户协议！")
        print("修改 eula.txt 第一行为 eula=true 后重新启动")
        input("按回车退出...")
        return

    print("✅ 协议已同意")

    if not os.path.exists(app_entry):
        print("\n❌ 找不到主程序 app/app.py")
        input("按回车退出...")
        return

    print("\n正在启动 app/app.py ...\n")
    # 独立子进程运行，和手动执行python效果完全一致，无导入路径问题
    subprocess.run([sys.executable, app_entry])

if __name__ == "__main__":
    main()