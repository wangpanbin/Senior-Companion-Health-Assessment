"""清理 benchmark 测试数据：30 个临时陪诊员 + 10000 条 BENCH 订单 + 抢单成功的 1228。

不动任何种子数据（301-330 陪诊员、64 条种子订单、e2e 测试账号 9001-9003）。
"""
import os
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
REDIS_CLI = r"D:\develop\Redis-8.8.0\redis-cli.exe"
COMP_START = 5001
COMP_END = 5030


def mysql_exec(sql: str) -> str:
    return subprocess.run(
        [MYSQL, "--host=127.0.0.1", "--port=3306", "--user=root",
         "--password=" + os.environ["MYSQL_PASSWORD"], "-D", "nianglin",
         "-N", "-B", "-e", sql],
        capture_output=True, text=True, check=True, encoding="utf-8"
    ).stdout.strip()


def redis_del(*keys: str) -> None:
    if keys:
        subprocess.run([REDIS_CLI, "DEL", *keys],
                       capture_output=True, text=True, encoding="utf-8")


def main():
    print("=" * 60)
    print("Benchmark 数据清理")
    print("=" * 60)

    # 1. 删 BENCH 订单
    print()
    print("Step 1: 删除 BENCH 订单")
    cnt = mysql_exec("SELECT COUNT(*) FROM companion_order WHERE order_no LIKE 'BENCH%'")
    print(f"  现有: {cnt} 条")
    mysql_exec("DELETE FROM companion_order WHERE order_no LIKE 'BENCH%'")
    print(f"  -> 已删除")

    # 2. 删抢单测试用的 1228 订单（含衍生）
    print()
    print("Step 2: 删除测试订单 1228 及其衍生（订单状态日志、打卡、轨迹、消息）")
    for table in ["order_status_log", "order_checkin", "companion_track",
                  "internal_message", "order_review", "complaint"]:
        try:
            n = mysql_exec(
                f"SELECT COUNT(*) FROM `{table}` WHERE order_id = 1228")
            if int(n) > 0:
                mysql_exec(f"DELETE FROM `{table}` WHERE order_id = 1228")
                print(f"  {table}: 删 {n} 条")
        except Exception as e:
            print(f"  {table}: 跳过（{e}）")
    mysql_exec("DELETE FROM companion_order WHERE id = 1228")
    print(f"  companion_order id=1228: 已删")

    # 3. 删 30 个临时陪诊员
    print()
    print(f"Step 3: 删除 {COMP_START}-{COMP_END} 临时陪诊员")
    cnt_profile = mysql_exec(
        f"SELECT COUNT(*) FROM companion_profile WHERE user_id BETWEEN {COMP_START} AND {COMP_END}")
    cnt_user = mysql_exec(
        f"SELECT COUNT(*) FROM sys_user WHERE id BETWEEN {COMP_START} AND {COMP_END}")
    print(f"  sys_user: {cnt_user} 条 / companion_profile: {cnt_profile} 条")
    mysql_exec(
        f"DELETE FROM companion_profile WHERE user_id BETWEEN {COMP_START} AND {COMP_END}")
    mysql_exec(
        f"DELETE FROM sys_user WHERE id BETWEEN {COMP_START} AND {COMP_END}")
    print(f"  -> 已删除")

    # 4. 删 Redis 里 bench* 的密码版本 + token 黑名单
    print()
    print("Step 4: 清理 Redis 临时键")
    keys = []
    for uid in range(COMP_START, COMP_END + 1):
        keys.append(f"pwd:version:{uid}")
    # DEL 用 shell 不支持 glob，用 Python 列模式
    # 简化：直接 DEL 这些具体 key
    redis_del(*keys)
    # 清 bench* 用户登录失败的密码版本（如果有）
    out = subprocess.run(
        [REDIS_CLI, "--scan", "--pattern", "captcha:bench*"],
        capture_output=True, text=True, encoding="utf-8"
    ).stdout.strip()
    if out:
        redis_del(*out.splitlines())
    print(f"  -> Redis 临时键已清理")

    # 5. 验证种子数据完整性
    print()
    print("Step 5: 验证种子数据完整性")
    seed_users = mysql_exec(
        "SELECT COUNT(*) FROM sys_user WHERE id BETWEEN 301 AND 330")
    seed_orders = mysql_exec(
        "SELECT COUNT(*) FROM companion_order WHERE order_no LIKE 'NL%'")
    bench_orders = mysql_exec(
        "SELECT COUNT(*) FROM companion_order WHERE order_no LIKE 'BENCH%'")
    bench_users = mysql_exec(
        f"SELECT COUNT(*) FROM sys_user WHERE id BETWEEN {COMP_START} AND {COMP_END}")
    print(f"  种子陪诊员 (301-330): {seed_users} (应为 30)")
    print(f"  种子订单 (NL%):        {seed_orders} (应为 64)")
    print(f"  BENCH 订单残留:        {bench_orders} (应为 0)")
    print(f"  临时用户残留:          {bench_users} (应为 0)")

    print()
    print("=" * 60)
    print("DONE")
    print("=" * 60)


if __name__ == "__main__":
    main()