# -*- coding: utf-8 -*-
"""
银龄伴诊 · 写路径 E2E 夹具（snapshot / restore / verify）
=========================================================

依据：docs/agents/FIXTURE_ROLLBACK_PLAN.md
目的：让「真实写接口」的 E2E 跑完后，库回到跑之前一模一样的状态，
      避免重演 BUG_LIST L1（internal_message 被污染到数千行）。

实现说明
--------
本机默认 Python（conda 3.14）没有 pymysql，故**不依赖任何 Python DB 驱动**，
直接驱动 `mysql.exe` 的批处理模式（`-N -B`：无表头 + Tab 分隔）取数与执行。

白名单行（会被写路径改动的种子行）用**影子表**整行备份：
      CREATE TABLE _e2e_bak_<t> AS SELECT * FROM <t> WHERE id IN (...)
还原时 `REPLACE INTO <t> SELECT * FROM _e2e_bak_<t>`。
这样列级零损耗、NULL / datetime / decimal / 长文本都不需要自己序列化解析。

水位（每张被写表的 MAX(id) 与 COUNT）记在
`reports/e2e/fixture/state.json`，restore 后改名 `state.done.json`。

用法（PowerShell）
------------------
    $env:MYSQL_PASSWORD = "<你的本机 dev 口令，不入库>"
    python tools/e2e/fixture.py snapshot
    python tools/e2e/fixture.py restore
    python tools/e2e/fixture.py verify

退出码：0 = 成功；非 0 = 失败（verify 有差异时非 0，供 CI / 报告判红）。
"""

import json
import os
import shutil
import subprocess
import sys
import time

# ⚠️ 中文 Windows 控制台默认代码页是 GBK(cp936)：成功提示里的 "✅"(U+2705) 编不出来，
#    最后那句 print 会抛 UnicodeEncodeError，让**所有检查都已通过**的 verify 以退出码 1 结束。
#    Playwright 的 globalTeardown 只看到非 0 退出码，于是报
#    「数据未回到基线 —— 回滚不干净，视为失败」的**假红**，数据其实是干净的。
#    这里把 stdout / stderr 统一切到 UTF-8（再兜一层 errors="replace"），与终端代码页无关。
for _stream in (sys.stdout, sys.stderr):
    try:
        _stream.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT_DIR = os.path.join(ROOT, "reports", "e2e", "fixture")
STATE = os.path.join(OUT_DIR, "state.json")
DONE = os.path.join(OUT_DIR, "state.done.json")

def _resolve_tool(env_var, local_default, path_name):
    """解析外部命令：环境变量 > 本机默认路径（存在时）> PATH。

    「本机默认路径优先于 PATH」是为了不改变既有开发机的行为（那里装的正是他们在用的客户端）；
    没有该路径的机器（干净 clone / 非 Windows）自动回落到 PATH，脚本因此可移植。
    """
    if os.environ.get(env_var):
        return os.environ[env_var]
    if os.path.exists(local_default):
        return local_default
    return shutil.which(path_name) or local_default


MYSQL_BIN = _resolve_tool("MYSQL_BIN", r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe", "mysql")
MYSQL_HOST = os.environ.get("MYSQL_HOST", "127.0.0.1")
MYSQL_PORT = os.environ.get("MYSQL_PORT", "3306")
MYSQL_USER = os.environ.get("MYSQL_USER", "root")
MYSQL_DB = os.environ.get("MYSQL_DB", "nianglin")
REDIS_CLI = _resolve_tool("REDIS_CLI", r"D:\develop\Redis-8.8.0\redis-cli.exe", "redis-cli")

# ------------------------------------------------------------------
# 被写表清单（来自 FIXTURE_ROLLBACK_PLAN.md §2 的「八条写路径 × 触达表矩阵」）
# 新增写接口时必须同步这里，否则漏清 —— 这正是 L1 的根因。
# ------------------------------------------------------------------
WRITE_TABLES = [
    "companion_order",
    "order_status_log",
    "order_reject_log",
    "internal_message",
    "order_checkin",
    "companion_track",
    "sys_file",
    "admin_oper_log",
    "order_review",
    "complaint",
    "medication_task",
    "medication_plan",
    "companion_audit_record",
    "companion_profile",
    "elder_profile",
    "family_elder_relation",
    "sys_user",
]

# 删除顺序：子表 / 副作用表在前，主表在后（V1 schema 无 FK，仅按业务顺序对齐）
DELETE_ORDER = [
    "order_status_log",
    "order_reject_log",
    "internal_message",
    "order_checkin",
    "companion_track",
    "sys_file",
    "admin_oper_log",
    "order_review",
    "complaint",
    "medication_task",
    "medication_plan",
    "companion_audit_record",
    "companion_profile",
    "elder_profile",
    "family_elder_relation",
    "sys_user",
    "companion_order",
]

# 白名单：被写路径改动过、必须原值回写的种子行（全列，含 version）
# key 是该表的业务键列名 —— companion_profile 用 user_id（其 id 是 601 起的代理键）
WHITELIST = [
    {"table": "companion_order", "key": "id", "ids": "1001,1007,1019,1031"},
    {"table": "elder_profile", "key": "id", "ids": "401,431"},
    {"table": "medication_task", "key": "id", "ids": "20002"},
    {"table": "medication_plan", "key": "id", "ids": "10001"},
    {"table": "companion_profile", "key": "user_id", "ids": "301,307,325"},
    {"table": "sys_user", "key": "id",
     "ids": "1,2,101," + ",".join(str(i) for i in range(201, 231)) + ",301"},
]

# 上传目录（打卡照片 / 资质证件等通过写接口落盘），跑前记清单，跑后删新增文件
UPLOAD_DIR = os.environ.get("UPLOAD_DIR", os.path.join(ROOT, "backend", "uploads"))


def die(msg):
    print("[FATAL] " + msg)
    sys.exit(2)


def mysql(args, sql=None, timeout=120):
    """执行 mysql CLI。返回 (rc, stdout, stderr)。"""
    cmd = [MYSQL_BIN, "-h", MYSQL_HOST, "-P", MYSQL_PORT, "-u", MYSQL_USER,
           "-D", MYSQL_DB, "--default-character-set=utf8mb4", "-N", "-B"]
    if sql is not None:
        cmd += ["-e", sql]
    cmd += list(args)
    if not os.environ.get("MYSQL_PASSWORD"):
        die("环境变量 MYSQL_PASSWORD 未设置（DB 口令一律走环境变量）")
    env = dict(os.environ)
    env["MYSQL_PWD"] = os.environ["MYSQL_PASSWORD"]  # 避免命令行明文告警
    p = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                       errors="replace", timeout=timeout, env=env)
    return p.returncode, p.stdout, p.stderr


def q(sql, timeout=120):
    """执行单条 SQL，返回行的列表（每行是列值的字符串列表）。"""
    rc, out, err = mysql([], sql, timeout)
    if rc != 0:
        die("SQL 失败: %s\n  SQL: %s\n  ERR: %s" % (rc, sql[:300], err.strip()[:500]))
    rows = []
    for line in out.splitlines():
        if line == "":
            continue
        rows.append(line.split("\t"))
    return rows


def exec_sql(sql, timeout=300):
    """执行可能含多条语句的 SQL（无返回）。"""
    rc, out, err = mysql([], sql, timeout)
    if rc != 0:
        die("SQL 失败: %s\n  SQL: %s\n  ERR: %s" % (rc, sql[:300], err.strip()[:500]))
    return out


def scalar(sql, default=None):
    rows = q(sql)
    if not rows or not rows[0]:
        return default
    return rows[0][0]


def redis(args, timeout=20):
    try:
        p = subprocess.run([REDIS_CLI] + args, capture_output=True, text=True,
                           encoding="utf-8", errors="replace", timeout=timeout)
        return p.returncode, (p.stdout or "").strip(), (p.stderr or "").strip()
    except Exception as e:
        return 1, "", str(e)


def redis_keys(pattern):
    rc, out, _ = redis(["KEYS", pattern])
    if rc != 0 or not out or out == "(empty array)":
        return []
    return [k for k in out.splitlines() if k.strip()]


def rel_files(root):
    """递归列出目录下所有文件的相对路径（用 / 统一分隔）。目录不存在则返回空集。"""
    found = set()
    if not os.path.isdir(root):
        return found
    for base, _dirs, files in os.walk(root):
        for fn in files:
            full = os.path.join(base, fn)
            found.add(os.path.relpath(full, root).replace(os.sep, "/"))
    return found


# ================================================================
# snapshot
# ================================================================
def snapshot(force=False):
    if os.path.exists(STATE) and not force:
        die("已存在未 restore 的状态文件 %s（防『脏基线再快照』）。\n"
            "      先跑 restore，或确认当前库干净后用 --force 覆盖。" % STATE)
    os.makedirs(OUT_DIR, exist_ok=True)

    state = {"ts": time.strftime("%Y-%m-%d %H:%M:%S"), "db": MYSQL_DB,
             "watermarks": {}, "shadows": [], "redis_seqs": {}, "uploads": []}

    # 1) 水位：每张被写表的 MAX(id) 与 COUNT
    for t in WRITE_TABLES:
        mx = scalar("SELECT COALESCE(MAX(id),0) FROM %s;" % t, "0")
        cnt = scalar("SELECT COUNT(*) FROM %s;" % t, "0")
        state["watermarks"][t] = {"max_id": int(mx), "count": int(cnt)}
    print("[snapshot] 水位已记录 %d 张表" % len(WRITE_TABLES))

    # 2) 白名单行 → 影子表
    for w in WHITELIST:
        t, key, ids = w["table"], w["key"], w["ids"]
        shadow = "_e2e_bak_" + t
        exec_sql("DROP TABLE IF EXISTS %s; CREATE TABLE %s AS SELECT * FROM %s WHERE %s IN (%s);"
                 % (shadow, shadow, t, key, ids))
        n = scalar("SELECT COUNT(*) FROM %s;" % shadow, "0")
        if int(n) == 0:
            die("白名单 %s（%s IN %s）影子表为空 —— 种子可能已变，请核对 ids。" % (t, key, ids))
        state["shadows"].append({"table": t, "shadow": shadow, "rows": int(n)})
        print("   影子表 %-22s ← %s 行 (%s in %s)" % (shadow, n, key, ids))

    # 3) Redis：订单号序列 + 密码版本号
    #    ⚠️ `pwd:version:<userId>` 会在**登出**时自增，且登出是**按用户**生效的
    #    （一次登出会作废该账号的全部令牌）。不还原它，跑过登出用例的账号
    #    下次跑就会全线 401。
    for pat in ("order:seq:*", "pwd:version:*"):
        for k in redis_keys(pat):
            _, v, _ = redis(["GET", k])
            state["redis_seqs"][k] = v
    print("[snapshot] Redis 记录 %d 个键（order:seq / pwd:version）" % len(state["redis_seqs"]))

    # 4) 上传目录清单（写接口会落盘打卡照片 / 证件）
    state["uploads"] = sorted(rel_files(UPLOAD_DIR))
    print("[snapshot] 上传目录已有 %d 个文件" % len(state["uploads"]))

    with open(STATE, "w", encoding="utf-8") as f:
        json.dump(state, f, ensure_ascii=False, indent=2)
    print("[snapshot] 写出 %s" % STATE)
    return 0


# ================================================================
# restore
# ================================================================
def restore():
    src = STATE if os.path.exists(STATE) else None
    if src is None:
        if os.path.exists(DONE):
            print("[restore] 已 restore 过（%s 存在），短路。" % DONE)
            return 0
        die("找不到 %s，无法 restore。" % STATE)
    with open(src, "r", encoding="utf-8") as f:
        state = json.load(f)

    wm = state["watermarks"]

    # 1) 删本跑新增的行（子表优先）
    deletes = []
    for t in DELETE_ORDER:
        if t in wm:
            deletes.append("DELETE FROM %s WHERE id > %d;" % (t, wm[t]["max_id"]))
    exec_sql("\n".join(deletes))
    print("[restore] 已删除 id 超水位的行（%d 张表）" % len(deletes))

    # 2) 白名单行原值回写（REPLACE 整行，含 version）
    for s in state["shadows"]:
        exec_sql("REPLACE INTO %s SELECT * FROM %s;" % (s["table"], s["shadow"]))
    print("[restore] 白名单行已从影子表回写 %d 张表" % len(state["shadows"]))

    # 3) 自增水位回落（可选，但让 id 输出可复现）
    for t, v in wm.items():
        try:
            exec_sql("ALTER TABLE %s AUTO_INCREMENT = %d;" % (t, v["max_id"] + 1))
        except SystemExit:
            print("   [warn] %s 的 AUTO_INCREMENT 回落失败（忽略）" % t)

    # 4) Redis：还原 order:seq / pwd:version
    for k, v in state["redis_seqs"].items():
        redis(["SET", k, v])
    print("[restore] Redis 已还原 %d 个键" % len(state["redis_seqs"]))

    # 5) 删除本跑新增的上传文件
    known = set(state.get("uploads") or [])
    new_files = sorted(rel_files(UPLOAD_DIR) - known)
    for rel in new_files:
        try:
            os.remove(os.path.join(UPLOAD_DIR, rel.replace("/", os.sep)))
        except OSError:
            pass
    print("[restore] 已删除本跑新增上传文件 %d 个" % len(new_files))
    for base, dirs, files in os.walk(UPLOAD_DIR, topdown=False):
        if not files and not dirs:
            try:
                os.rmdir(base)
            except OSError:
                pass

    # 6) 状态文件改名（幂等）
    os.replace(src, DONE)
    print("[restore] 完成；状态改名 → %s" % DONE)
    return 0


# ================================================================
# verify
# ================================================================
def verify():
    src = DONE if os.path.exists(DONE) else (STATE if os.path.exists(STATE) else None)
    if src is None:
        die("找不到状态文件（先 snapshot / restore）。")
    with open(src, "r", encoding="utf-8") as f:
        state = json.load(f)

    wm = state["watermarks"]
    problems = []

    # 1) 行数 == 基线；max_id <= 水位
    for t, v in wm.items():
        cur_cnt = int(scalar("SELECT COUNT(*) FROM %s;" % t, "-1"))
        cur_max = int(scalar("SELECT COALESCE(MAX(id),0) FROM %s;" % t, "-1"))
        ok_cnt = cur_cnt == v["count"]
        ok_max = cur_max <= v["max_id"]
        flag = "OK" if (ok_cnt and ok_max) else "!!"
        print("  [%s] %-24s count %d/%d  max_id %d/%d"
              % (flag, t, cur_cnt, v["count"], cur_max, v["max_id"]))
        if not ok_cnt:
            problems.append("%s 行数 %d != 基线 %d" % (t, cur_cnt, v["count"]))
        if not ok_max:
            problems.append("%s max_id %d > 水位 %d" % (t, cur_max, v["max_id"]))

    # 2) 白名单行逐列等价（NULL-safe <=>），比对后删影子表
    for s in state["shadows"]:
        t, shadow = s["table"], s["shadow"]
        try:
            cols = [r[0] for r in q("SHOW COLUMNS FROM %s;" % t)]
            cols = [c for c in cols if c.lower() != "id"]
            where = " OR ".join("NOT (b.`%s` <=> o.`%s`)" % (c, c) for c in cols)
            diff = int(scalar("SELECT COUNT(*) FROM %s b JOIN %s o ON o.id=b.id WHERE %s;"
                              % (shadow, t, where), "-1"))
            missing = int(scalar("SELECT COUNT(*) FROM %s b LEFT JOIN %s o ON o.id=b.id "
                                 "WHERE o.id IS NULL;" % (shadow, t), "-1"))
            if diff == 0 and missing == 0:
                print("  [OK] %-24s 白名单行 %d 行逐列一致" % (t, s["rows"]))
            else:
                problems.append("%s 白名单行不一致：diff=%d missing=%d" % (t, diff, missing))
            exec_sql("DROP TABLE IF EXISTS %s;" % shadow)
        except SystemExit:
            problems.append("%s 影子表比对失败" % t)

    # 3) 成对性断言：已评价订单数 == order_review 行数
    reviewed = int(scalar("SELECT COUNT(*) FROM companion_order WHERE status='REVIEWED';", "-1"))
    reviews = int(scalar("SELECT COUNT(*) FROM order_review;", "-1"))
    if reviewed == reviews:
        print("  [OK] 成对性：REVIEWED 订单 %d == order_review %d" % (reviewed, reviews))
    else:
        problems.append("成对性不符：REVIEWED 订单 %d != order_review %d" % (reviewed, reviews))

    if problems:
        print("\n[verify] 不一致 %d 项：" % len(problems))
        for p in problems:
            print("   - " + p)
        return 1
    print("\n[verify] 全部回到基线 ✅")
    return 0


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    force = "--force" in sys.argv
    if cmd == "snapshot":
        return snapshot(force)
    if cmd == "restore":
        return restore()
    if cmd == "verify":
        return verify()
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
