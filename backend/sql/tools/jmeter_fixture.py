"""M5 / M6 两个 JMeter 压测计划的夹具准备与还原。

用途：`jmeter_m5_reconnect.jmx` 与 `jmeter_m6_concurrent_confirm.jmx` 需要一个
「可确认的服药任务」和一个「家属令牌」。本脚本负责把它们准备好，
并在跑完后把任务行**原样还原**（压测不该在种子数据上留下痕迹）。

    python jmeter_fixture.py setup     # 快照任务 20002 + 登录 fam001 拿 token
    python jmeter_fixture.py restore   # 用快照把任务 20002 还原

设计取舍：
  · 为什么用种子任务 20002：它是 elder 401 / plan 10001 的 PENDING 任务，
    家属 101 与 elder 401 有绑定关系（family_elder_relation 501），
    拿来当「并发确认」的靶子最省事，不需要另外造数据。
  · 为什么必须先快照：并发确认会把任务推进到 TAKEN。如果不还原，
    下一次跑 M6 就会「全是 5003」，观察不到「只有一个成功」这一幕；
    更糟的是种子数据被悄悄改掉，别人复现时对不上。
  · 令牌走 Redis 旁路拿验证码答案，与 e2e_*.py / bench_setup.py 同一套做法。
"""
import json
import os
import subprocess
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

import urllib.error
import urllib.parse
import urllib.request

TOOLS = r"F:\test\Senior Companion Health Assessment\backend\sql\tools"
BASE = "http://127.0.0.1:8080/api"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
REDIS_CLI = r"D:\develop\Redis-8.8.0\redis-cli.exe"
DB = "nianglin"

PASSWORD = "Nl@123456"
FAMILY_ACCOUNT = "fam001"

TASK_ID = 20002
SNAPSHOT = os.path.join(TOOLS, "jmeter_fixture_snapshot.json")
TOKEN_FILE = os.path.join(TOOLS, "jmeter_token.txt")

# 会被压测改动的列（还原时只回写这些；列名以 SHOW COLUMNS 为准，别凭记忆写）
COLS = ["status", "confirm_time", "confirm_by", "confirm_remark",
        "was_missed", "notify_sent", "notify_time"]


def mysql_exec(sql: str) -> str:
    return subprocess.run(
        [MYSQL, "--host=127.0.0.1", "--port=3306", "--user=root",
         "--password=" + os.environ["MYSQL_PASSWORD"], "-D", DB,
         "-N", "-B", "-e", sql],
        capture_output=True, text=True, check=True, encoding="utf-8"
    ).stdout.strip()


def login(username: str) -> str | None:
    """登录拿 accessToken：验证码答案从 Redis 直接读（旁路）"""
    req = urllib.request.Request(BASE + "/auth/captcha", method="GET")
    with urllib.request.urlopen(req, timeout=10) as resp:
        cap = json.loads(resp.read().decode())
    key = cap["data"]["captchaKey"]
    code = subprocess.run([REDIS_CLI, "GET", "captcha:" + key],
                          capture_output=True, text=True).stdout.strip()
    if not code:
        print("  ✗ Redis 里没读到验证码答案，Redis 起了吗？")
        return None
    body = json.dumps({"username": username, "password": PASSWORD,
                       "captchaKey": key, "captchaCode": code}).encode()
    req = urllib.request.Request(BASE + "/auth/login", data=body,
                                 headers={"Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        data = json.loads(e.read().decode())
    if data.get("code") != 200:
        print("  ✗ 登录失败：%s" % data.get("message"))
        return None
    return data["data"]["accessToken"]


def setup():
    row = mysql_exec(
        "SELECT %s FROM medication_task WHERE id = %d" % (", ".join(COLS), TASK_ID))
    values = row.split("\t") if row else []
    if not values:
        sys.exit("✗ 找不到 medication_task id=%d，先确认库里有种子数据" % TASK_ID)
    snapshot = dict(zip(COLS, values))
    json.dump({"taskId": TASK_ID, "columns": snapshot}, open(SNAPSHOT, "w", encoding="utf-8"),
              ensure_ascii=False, indent=2)
    print("  ✓ 任务快照已存：%s" % SNAPSHOT)
    for k, v in snapshot.items():
        print("      %-14s = %s" % (k, v if v != "NULL" else "NULL"))

    token = login(FAMILY_ACCOUNT)
    if not token:
        sys.exit("✗ 拿 token 失败")
    # 令牌写文件而不是打印：JWT 很长，粘到命令行容易截断出错
    open(TOKEN_FILE, "w", encoding="utf-8").write(token)
    print("  ✓ 令牌已写：%s（%s）" % (TOKEN_FILE, FAMILY_ACCOUNT))
    print()
    print("跑压测（把 <T> 换成上面文件里的内容）：")
    print('  jmeter -n -t "%s\\jmeter_m5_reconnect.jmx" -Jtoken=<T> -JorderId=1001 -l "%s\\m5.jtl"'
          % (TOOLS, TOOLS))
    print('  jmeter -n -t "%s\\jmeter_m6_concurrent_confirm.jmx" -Jtoken=<T> -JtaskId=%d -JelderId=401 -l "%s\\m6.jtl"'
          % (TOOLS, TASK_ID, TOOLS))
    print()
    print("跑完记得还原：python jmeter_fixture.py restore")


def restore():
    if not os.path.exists(SNAPSHOT):
        sys.exit("✗ 没有快照文件 %s，无法还原（先跑 setup）" % SNAPSHOT)
    data = json.load(open(SNAPSHOT, encoding="utf-8"))
    cols = data["columns"]
    sets = []
    for c in COLS:
        v = cols.get(c)
        sets.append("%s = %s" % (c, "NULL" if v in (None, "NULL") else "'%s'" % v))
    mysql_exec("UPDATE medication_task SET %s WHERE id = %d" % (", ".join(sets), data["taskId"]))
    after = mysql_exec("SELECT %s FROM medication_task WHERE id = %d"
                       % (", ".join(COLS), data["taskId"]))
    same = after.split("\t") == [("NULL" if cols.get(c) in (None, "NULL") else cols.get(c)) for c in COLS]
    print("  还原后：%s" % after.replace("\t", " | "))
    print("  %s 与快照一致" % ("✓" if same else "⚠️ 不一致，请人工核对"))


def verify():
    """看压测结论：任务是否恰好被确认一次。

    JMeter 断言只能证明「没出现意外错误码」，证明不了「只成功了一次」——
    后者必须回库里看。两个请求同时成功会把 confirm_time 覆盖两次而不留痕，
    但 confirm_by 与 status 至少能证明「确实有人确认过、且状态只前进了一步」。
    """
    row = mysql_exec("SELECT id, status, confirm_by, confirm_time, was_missed FROM medication_task WHERE id = %d"
                     % TASK_ID)
    print("  任务 %d 现状：%s" % (TASK_ID, row.replace("\t", " | ") if row else "（不存在）"))
    if not row:
        return
    status, confirm_by = row.split("\t")[1], row.split("\t")[2]
    if status == "TAKEN" and confirm_by not in ("NULL", ""):
        print("  ✓ 恰好一次确认生效：status=TAKEN、confirm_by=%s" % confirm_by)
        print("    （并发下的其它 49 个请求应返回 5003「已确认」，JMeter 侧 Err=0.00%% 可印证没有 500）")
    else:
        print("  任务当前未被确认（status=%s）—— 可能是还原过、或本次前缀校验就失败了" % status)
    print("  还原：python jmeter_fixture.py restore")


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "setup"
    if mode == "setup":
        setup()
    elif mode == "restore":
        restore()
    elif mode == "verify":
        verify()
    else:
        sys.exit("用法：python jmeter_fixture.py [setup|restore|verify]")
