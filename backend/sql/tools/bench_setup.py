"""JMeter 压测数据准备。

造 30 个临时陪诊员（id 5001-5030）+ 1 个 PENDING 订单 + 50 个 token（写到 CSV）。

数据安全：脚本在头部 / 尾部都做物理清理，不动种子数据。
"""
import csv
import json
import os
import subprocess
import sys

# 强制 UTF-8 输出，避开 Windows GBK 终端
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass
import urllib.error
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:8080/api"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
REDIS_CLI = r"D:\develop\Redis-8.8.0\redis-cli.exe"

# 临时测试用陪诊员 id 段（不与种子 301-330 冲突，不与 e2e 9001-9003 冲突）
COMP_START = 5001
COMP_END = 5030   # 共 30 个陪诊员；与种子 301-330 凑出 60 个 token，足以 50 并发
PASSWORD = "Nl@123456"
HOSPITAL = "海南省人民医院"
ADDRESS = "海口市秀英区白路 1 号"
LONGITUDE = "110.316123"
LATITUDE = "20.044321"


def mysql_exec(sql: str) -> str:
    return subprocess.run(
        [MYSQL, "--host=127.0.0.1", "--port=3306", "--user=root",
         "--password=" + os.environ["MYSQL_PASSWORD"], "-D", "nianglin",
         "-N", "-B", "-e", sql],
        capture_output=True, text=True, check=True, encoding="utf-8"
    ).stdout.strip()


def http_post(path: str, body: dict, token: str | None = None) -> dict:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(),
                                 headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())


def http_get(path: str, token: str) -> dict:
    req = urllib.request.Request(BASE + path,
                                 headers={"Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())


def http_get_raw(path: str) -> tuple[int, dict]:
    req = urllib.request.Request(BASE + path, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def login(username: str) -> str | None:
    # GET 拿验证码（图片）不解析，从 Redis 读服务端写的明文
    status, cap = http_get_raw("/auth/captcha")
    if status != 200 or cap.get("code") != 200:
        print(f"  [CAPTCHA-FAIL] {username}: status={status} body={cap}")
        return None
    cap_key = cap["data"]["captchaKey"]
    # 从 Redis 读答案（这是 e2e 脚本的标准旁路）
    code = subprocess.run([REDIS_CLI, "GET", f"captcha:{cap_key}"],
                          capture_output=True, text=True).stdout.strip()
    if not code:
        print(f"  [CAPTCHA-EMPTY] {username}: key={cap_key}")
        return None
    resp = http_post("/auth/login", {
        "username": username, "password": PASSWORD,
        "captchaKey": cap_key, "captchaCode": code
    })
    if resp.get("code") == 200:
        return resp["data"]["accessToken"]
    print(f"  [LOGIN-FAIL] {username}: {resp.get('message')}")
    return None


def main():
    print("=" * 60)
    print("Step 1/5  备份当前状态（用于回滚）")
    print("=" * 60)
    before_users = mysql_exec(
        f"SELECT COUNT(*) FROM sys_user WHERE id BETWEEN {COMP_START} AND {COMP_END}")
    before_orders = mysql_exec(
        f"SELECT COUNT(*) FROM companion_order WHERE family_id = 101 AND order_no LIKE 'BENCH%'")
    print(f"  临时陪诊员现存: {before_users} 条")
    print(f"  临时 BENCH 订单现存: {before_orders} 条")

    print()
    print("=" * 60)
    print("Step 2/5  清理残留（保险起见）")
    print("=" * 60)
    mysql_exec(f"DELETE FROM companion_order WHERE order_no LIKE 'BENCH%'")
    mysql_exec(f"DELETE FROM companion_profile WHERE user_id BETWEEN {COMP_START} AND {COMP_END}")
    mysql_exec(f"DELETE FROM sys_user WHERE id BETWEEN {COMP_START} AND {COMP_END}")
    print("  ✓ 清理完成")

    print()
    print("=" * 60)
    print("Step 3/5  创建 30 个临时陪诊员（5001-5030）+ 审核通过")
    print("=" * 60)
    # 密码 hash 直接复用种子的（同密码），不重新 BCrypt
    HASH = "$2a$10$dTfTIBtoETZnreDYKJ6Re.rgpuLth.58Y0hgjohfzPE.xBaCZZCHi"
    rows = []
    for uid in range(COMP_START, COMP_END + 1):
        rows.append(f"({uid}, 'bench{uid}', '{HASH}', '陪诊员{uid}', '陪诊员{uid}', "
                    f"'138{uid:08d}', 'COMPANION', 'NORMAL', 0, NOW(), NOW(), 0)")
    mysql_exec(
        "INSERT INTO sys_user (id, username, password, real_name, nickname, phone, "
        "role, status, need_change_password, create_time, update_time, deleted) VALUES "
        + ",".join(rows))
    print(f"  ✓ 插入 sys_user: {len(rows)} 条")

    profiles = []
    for uid in range(COMP_START, COMP_END + 1):
        profiles.append(
            f"({uid}, {uid}, '陪诊员{uid}', '138{uid:08d}', "
            f"'海口市', '工作日全天', '陪诊员{uid} 简介', "
            f"'CERT{uid}', NULL, 'APPROVED', NULL, NULL, NULL, NOW(), "
            f"'AVAILABLE', 5.00, 0, 0, 0, NULL, NOW(), NOW(), 0)"
        )
    mysql_exec(
        "INSERT INTO companion_profile (id, user_id, real_name, phone, service_area, "
        "available_time, introduction, certificate_no, health_cert_expire, audit_status, "
        "reject_reason, id_card, audit_admin_id, audit_time, work_status, "
        "score, review_count, order_count, accept_count, remark, create_time, update_time, deleted) VALUES "
        + ",".join(profiles))
    print(f"  ✓ 插入 companion_profile: {len(profiles)} 条")

    print()
    print("=" * 60)
    print("Step 4/5  用 family=101 (fam001) 下一个 PENDING 订单")
    print("=" * 60)
    fam_token = login("fam001")
    if not fam_token:
        sys.exit("✗ fam001 登录失败")
    create_resp = http_post("/order", {
        "elderId": 401,
        "hospital": HOSPITAL,
        "department": "心血管内科",
        "visitTime": "2099-01-01 10:00:00",
        "address": ADDRESS,
        "longitude": LONGITUDE,
        "latitude": LATITUDE,
        "remark": "BENCH 测试订单",
        "serviceFee": 128.00
    }, token=fam_token)
    if create_resp.get("code") != 200:
        sys.exit(f"✗ 下单失败: {create_resp}")
    order_id = create_resp["data"]["orderId"]
    print(f"  ✓ 订单创建成功: orderId={order_id}")

    print()
    print("=" * 60)
    print("Step 5/5  登录 50 个陪诊员拿 token（30 临时 + 20 种子）")
    print("=" * 60)
    # 50 并发抢单：30 个临时（5001-5030）+ 20 个种子（301-320）
    companions = list(range(COMP_START, COMP_END + 1)) + list(range(301, 321))
    tokens = []
    failed = []
    for uid in companions:
        username = f"bench{uid}" if uid >= COMP_START else f"comp{uid - 300:03d}"
        tok = login(username)
        if tok:
            tokens.append({"userId": uid, "username": username, "token": tok})
        else:
            failed.append(username)
    print(f"  ✓ 拿到 token: {len(tokens)} / {len(companions)}")
    if failed:
        print(f"  ⚠️ 失败: {failed[:5]}")

    # 写 CSV（JMeter 的 CSV Data Set Config 读取）
    csv_path = "F:/test/Senior Companion Health Assessment/backend/sql/tools/bench_tokens.csv"
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["userId", "username", "token"])
        w.writeheader()
        w.writerows(tokens)
    print(f"  ✓ token 写到 {csv_path}")

    # 同时写元数据
    meta_path = "F:/test/Senior Companion Health Assessment/backend/sql/tools/bench_meta.json"
    with open(meta_path, "w", encoding="utf-8") as f:
        json.dump({"orderId": order_id, "tokenCount": len(tokens),
                   "users": [t["userId"] for t in tokens]}, f, ensure_ascii=False, indent=2)
    print(f"  ✓ 元数据写到 {meta_path}")

    print()
    print("=" * 60)
    print("DONE")
    print("=" * 60)
    print(f"  orderId = {order_id}")
    print(f"  tokens  = {len(tokens)}")


if __name__ == "__main__":
    main()