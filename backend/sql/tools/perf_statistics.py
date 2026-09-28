"""M10 统计接口万条数据压测（对应 plan.md L387）。

**验收标准**：统计接口在 1 万条订单数据下响应时间 < 2s。

**做法**：同一组端点在「灌数据前 / 灌 1 万条后」各测 N 次取中位数，
对比绝对耗时与增量耗时。只有后者才是这条验收标准真正要考的东西 ——
存量 68 条时的 8ms 不能拿来证明「万条也够快」。

**数据安全**：造的数据全部带 `PERF%` 前缀，测完在 finally 里物理删除；
不碰任何种子数据。脚本可重复执行。

对应约束见 `docs/agents/reports/BASELINE_2026-09-28.md` §6.1（凭据单一真源）。
"""
import json
import os
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

# 强制 UTF-8 输出，避开 Windows GBK 终端
try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

BASE = "http://127.0.0.1:8080/api"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
REDIS_CLI = r"D:\develop\Redis-8.8.0\redis-cli.exe"

# 造数标记：所有临时订单 order_no 都以它开头，回滚就靠这一条 WHERE
ORDER_PREFIX = "PERF"
# 造数规模 —— 验收标准要求的下限
TARGET_ROWS = 10000
# 造数时间跨度（天）：铺开在默认统计窗口内，让趋势图 / 排行榜真的走聚合
SPAN_DAYS = 28
# 每个端点的重复次数，取中位数抗抖动
ROUNDS = 5

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from fixture_credentials import PASSWORD as SEED_PASSWORD  # noqa: E402  种子口令单一真源

# 种子陪诊员（301-325，均为 audit_status=APPROVED），排行榜聚合需要
COMPANIONS = list(range(301, 326))
# 种子家属 / 老人档案
FAMILY_ID = 101
ELDER_ID = 401

# 待测端点：覆盖 M10 的 5 个只读聚合接口
ENDPOINTS = [
    ("overview", "/statistics/overview"),
    ("order-trend", "/statistics/order-trend"),
    ("order-status", "/statistics/order-status"),
    ("companion-rank", "/statistics/companion-rank"),
    ("medication-missed", "/statistics/medication-missed"),
]


def mysql_exec(sql: str) -> str:
    return subprocess.run(
        [MYSQL, "--host=127.0.0.1", "--port=3306", "--user=root",
         "--password=" + os.environ["MYSQL_PASSWORD"], "-D", "nianglin",
         "-N", "-B", "-e", sql],
        capture_output=True, text=True, check=True, encoding="utf-8"
    ).stdout.strip()


def http_get(path: str, token: str) -> dict:
    req = urllib.request.Request(BASE + path,
                                 headers={"Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
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


def http_post(path: str, body: dict) -> dict:
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"},
                                 method="POST")
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())


def login(username: str) -> str:
    """管理员登录。验证码走 Redis 明文旁路 —— 与 e2e_*.py 同一套做法。"""
    status, cap = http_get_raw("/auth/captcha")
    if status != 200 or cap.get("code") != 200:
        sys.exit(f"✗ 取验证码失败: status={status} body={cap}")
    cap_key = cap["data"]["captchaKey"]
    code = subprocess.run([REDIS_CLI, "GET", f"captcha:{cap_key}"],
                          capture_output=True, text=True).stdout.strip()
    if not code:
        sys.exit(f"✗ 验证码为空: key={cap_key}")
    resp = http_post("/auth/login", {
        "username": username, "password": SEED_PASSWORD,
        "captchaKey": cap_key, "captchaCode": code
    })
    if resp.get("code") != 200:
        sys.exit(f"✗ {username} 登录失败: {resp.get('message')}")
    return resp["data"]["accessToken"]


def query_string() -> str:
    """显式指定 30 天窗口，避免依赖默认值 —— 默认值改了会污染前后对比。"""
    import datetime
    end = datetime.date.today()
    start = end - datetime.timedelta(days=29)
    return ("?startDate=" + start.isoformat() + "&endDate=" + end.isoformat())


def bench(token: str, label: str) -> dict:
    """跑一轮：每个端点 ROUNDS 次，返回中位耗时（毫秒）。"""
    result = {}
    for name, path in ENDPOINTS:
        url = path + query_string()
        samples = []
        code_seen = None
        for _ in range(ROUNDS):
            t0 = time.perf_counter()
            resp = http_get(url, token)
            samples.append((time.perf_counter() - t0) * 1000)
            code_seen = resp.get("code")
        result[name] = {
            "median_ms": round(statistics.median(samples), 1),
            "max_ms": round(max(samples), 1),
            "code": code_seen,
        }
        print(f"  {label:<8} {name:<18} 中位 {result[name]['median_ms']:>8.1f} ms"
              f"  最慢 {result[name]['max_ms']:>8.1f} ms  code={code_seen}")
    return result


def seed_orders() -> int:
    """灌 10000 条订单。用递归 CTE 生成序号，MySQL 默认递归上限 1000 故先放开。"""
    # 注意：SET SESSION 与 INSERT 必须在**同一次** mysql 调用里。
    # mysql_exec 每次起一个独立进程，会话不共享，拆开写上限仍是默认的 1000。
    companion_list = ",".join(str(c) for c in COMPANIONS)
    # ELT 的第 2 个参数必须是**逗号分隔的独立实参**，不能写成 ('A','B',...)。
    # 写成括号元组时 MySQL 会把它当行表达式，报 ERROR 1241 Operand should
    # contain 1 column(s) —— 这个错只在灌数时才炸，报错信息完全指不到括号上。
    statuses = ("'PENDING','ACCEPTED','IN_SERVICE','COMPLETED',"
                "'REVIEWED','CANCELLED','COMPLETED','REVIEWED'")
    sql = f"""
        SET SESSION cte_max_recursion_depth = 100000;
        INSERT INTO companion_order
            (order_no, family_id, elder_id, companion_id, hospital, department,
             visit_time, address, status, fee, payment_status,
             accept_time, start_time, finish_time, create_time, update_time, deleted)
        WITH RECURSIVE seq(n) AS (
            SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < {TARGET_ROWS}
        )
        SELECT
            CONCAT('{ORDER_PREFIX}', LPAD(n, 8, '0')),
            {FAMILY_ID},
            {ELDER_ID},
            ELT(1 + MOD(n, {len(COMPANIONS)}), {companion_list}),
            '海南省人民医院',
            ELT(1 + MOD(n, 5), '心血管内科', '内分泌科', '骨科', '神经内科', '呼吸内科'),
            DATE_SUB(NOW(), INTERVAL (MOD(n, {SPAN_DAYS}) + 1) DAY) + INTERVAL 9 HOUR,
            '海口市秀英区白路 1 号',
            ELT(1 + MOD(n, 8), {statuses}),
            ROUND(88 + MOD(n, 120) + 0.00, 2),
            ELT(1 + MOD(n, 2), 'UNPAID', 'SETTLED'),
            DATE_SUB(NOW(), INTERVAL (MOD(n, {SPAN_DAYS}) + 1) DAY) + INTERVAL 10 HOUR,
            DATE_SUB(NOW(), INTERVAL (MOD(n, {SPAN_DAYS}) + 1) DAY) + INTERVAL 11 HOUR,
            DATE_SUB(NOW(), INTERVAL (MOD(n, {SPAN_DAYS}) + 1) DAY) + INTERVAL 13 HOUR,
            DATE_SUB(NOW(), INTERVAL (MOD(n, {SPAN_DAYS}) + 1) DAY)
                     + INTERVAL (MOD(n, 720) + 1) MINUTE,
            NOW(),
            0
        FROM seq
    """
    mysql_exec(sql)
    return int(mysql_exec(
        f"SELECT COUNT(*) FROM companion_order WHERE order_no LIKE '{ORDER_PREFIX}%'"))


def cleanup() -> None:
    """物理删除造数。按前缀删，不碰种子。"""
    n = mysql_exec(
        f"DELETE FROM companion_order WHERE order_no LIKE '{ORDER_PREFIX}%'; "
        f"SELECT ROW_COUNT();")
    print(f"  ✓ 已清理 {n} 条 {ORDER_PREFIX} 造数")


def main():
    print("=" * 72)
    print("M10 统计接口万条压测（plan.md L387）")
    print("=" * 72)

    token = login("admin")
    print("  ✓ admin 登录成功")

    before_n = mysql_exec("SELECT COUNT(*) FROM companion_order WHERE deleted=0")
    print(f"  灌数前订单总数: {before_n}")
    print()
    print("── 灌数前基线 ──")
    base = bench(token, "基线")

    print()
    print("── 灌 10000 条订单 ──")
    t0 = time.perf_counter()
    seeded = seed_orders()
    print(f"  ✓ 灌入 {seeded} 条，耗时 {time.perf_counter() - t0:.1f}s")
    after_n = mysql_exec("SELECT COUNT(*) FROM companion_order WHERE deleted=0")
    print(f"  灌数后订单总数: {after_n}")

    print()
    print("── 灌数后（每端点取 %d 次中位）──" % ROUNDS)
    load = bench(token, "万条")

    print()
    print("── 对比 ──")
    print(f"  {'端点':<18}{'基线(ms)':>12}{'万条(ms)':>12}{'增量(ms)':>12}  判定")
    rows = []
    for name, _ in ENDPOINTS:
        b = base[name]["median_ms"]
        ld = load[name]["median_ms"]
        delta = round(ld - b, 1)
        ok = ld < 2000
        rows.append((name, b, ld, delta, ok))
        print(f"  {name:<18}{b:>12.1f}{ld:>12.1f}{delta:>12.1f}  "
              f"{'PASS (<2000ms)' if ok else 'FAIL (>=2000ms)'}")
    slow = [r for r in rows if not r[4]]
    print()
    print("=" * 72)
    print("结论: " + (f"全部 {len(rows)} 个端点均 < 2000ms ✓"
                     if not slow else f"FAIL —— {len(slow)} 个端点超标: "
                                       + ", ".join(r[0] for r in slow)))
    print("=" * 72)
    return slow


if __name__ == "__main__":
    try:
        failed = main()
    finally:
        print()
        print("回滚造数...")
        try:
            cleanup()
        except Exception as e:  # noqa: BLE001
            print(f"  ✗ 清理失败（需人工处理）: {e}")
            sys.exit(2)
    sys.exit(1 if failed else 0)
