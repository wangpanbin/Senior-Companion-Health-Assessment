#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""银龄伴诊 · 把数据库 / Redis 恢复到「干净基线」（Python 重写版）

适用场景
--------
E2E 写路径测试（Playwright / 压测）污染了种子数据，导致
`internal_message` / `companion_order` 等表的行数与 FRONTEND_CONTRACT §10.8
不一致，依赖绝对条数的断言开始假失败
（详见 `reports/playwright/e2e-report.md` §F-04 与 `docs/agents/BUG_LIST.md` L1）。

⚠️ 破坏性操作：本脚本会 DELETE 写路径新增行，**不可逆**。
   默认走 dry-run，只打印计划；必须显式 `--execute` 才真删。

用法
----
    # 1) 看计划（推荐先做，不碰任何数据）
    python tools/e2e/restore_clean_baseline.py

    # 2) 真执行 —— 会二次确认；输入 yes 继续
    python tools/e2e/restore_clean_baseline.py --execute

    # 3) 跳过二次确认（CI / 自动化场景，自己负责）
    python tools/e2e/restore_clean_baseline.py --execute --yes

    # 只清某几张表
    python tools/e2e/restore_clean_baseline.py --execute --tables internal_message,companion_order

    # 只看计划、且连 Redis 都不碰
    python tools/e2e/restore_clean_baseline.py --skip-redis

DB 口令一律走环境变量 `MYSQL_PASSWORD`，不落盘、不进 argv
（原 PowerShell 版用 `-p"$env:MYSQL_PASSWORD"`，会出现在进程命令行里）。
Redis 客户端按 `REDIS_CLI` > 本机默认路径 > `PATH` 解析。

--------------------------------------------------------------------------
与旧 PowerShell 版（restore_clean_baseline.ps1）的三处差异
--------------------------------------------------------------------------
1. **不再用 `WHERE id > (MAX(id) - 100)` 这种猜测式兜底。**
   旧版对「没有已知基线」的表拼出 `DELETE FROM t WHERE id > (MAX(id) - 100);`，
   这是**非法 MySQL**（`ERROR 1111 (HY000): Invalid use of group function`，
   聚合函数不能出现在 WHERE 里）—— 实测 16/17 张表会直接失败。
   而且即便把语法改对，「删掉 id 最大的 100 行」也不是清理污染，
   它会**删掉 100 行真实种子数据**。
   本版改为：**从种子 SQL 里解析真实基线**（`V2`/`V3`/`V4` 的 INSERT 语句
   是唯一真源），解析不出来的表**默认跳过**并在输出里明确列出。

2. **不再硬编码一张可能不存在的表清单。**
   旧版 17 张表里有 3 张（`medication_log` / `execution_photo` /
   `elder_emergency_contact`）在本库**根本不存在**，会逐个报
   `ERROR 1146 (42S02): Table doesn't exist`。
   本版运行时查 `information_schema.tables`，与候选清单**取交集**后再进计划。

3. **不再吞掉 stderr。**
   旧版每条 mysql 调用都带 `2>$null`，SQL 报错时 `affected` 变成空字符串，
   脚本照样逐行打印、最后打印「✅ 完成」并 **exit 0** ——
   「跑了一次清理」既不生效也不报错，是最坏的一种失败。
   本版**原样打印 stderr**，任一表失败即**退出码非 0**，
   并在结尾汇总成功/失败/跳过三态。
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# 常量
# ---------------------------------------------------------------------------

# 17 张「写路径会触动」的表（与 FIXTURE_ROLLBACK_PLAN §3 一致）。
# 这里只是**候选**：运行时会与实际存在的表取交集，不存在的自动剔除。
CANDIDATE_TABLES = [
    "companion_order",
    "order_checkin",
    "companion_track",
    "order_review",
    "complaint",
    "medication_task",
    "medication_plan",
    "internal_message",
    "sys_login_log",
    "admin_oper_log",
    "companion_audit_record",
    "medication_log",
    "execution_photo",
    "elder_emergency_contact",
    "family_elder_relation",
    "companion_profile",
    "sys_user",
]

DB_NAME = "nianglin"
DB_HOST = "127.0.0.1"
DB_PORT = "3306"
DB_USER = "root"

# 种子 SQL 脚本（唯一真源）。V1 是 DDL 没有 INSERT；V2/V3 是种子与边界数据。
SEED_SQL_FILES = ["V2__seed_data.sql", "V3__seed_boundary.sql", "V4__order_fee_item.sql"]

# 手工确认过的基线（比解析更权威，优先级最高）。
# internal_message 的 40072 同时见于：
#   - FRONTEND_CONTRACT.md §10.7/§10.8
#   - backend/sql/tools/README.md 的历史记录
#   - 2026-09-29 审计报告
SEED_MAX_IDS = {
    "internal_message": 40072,
}

# 删除行数超过这个比例 / 绝对数时中止，除非 --force。
# 目的是把「基线解析错了」这种事故挡在删除动作之前。
DELETE_WARN_RATIO = 0.30
DELETE_WARN_ABS = 500

MYSQL_DEFAULT_PATHS = [
    r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe",
    r"C:\Program Files\MySQL\MySQL Server 8.1\bin\mysql.exe",
    "/usr/local/mysql/bin/mysql",
    "/usr/bin/mysql",
]
REDIS_DEFAULT_PATHS = [
    r"D:\develop\Redis-8.8.0\redis-cli.exe",
    r"C:\Program Files\Redis\redis-cli.exe",
    "/usr/local/bin/redis-cli",
    "/usr/bin/redis-cli",
]
REDIS_PATTERNS = ["order:seq:*", "pwd:version:*"]


# ---------------------------------------------------------------------------
# 外部命令解析与执行
# ---------------------------------------------------------------------------

def find_binary(env_var, defaults, name):
    """按「环境变量 > 本机默认路径（存在时）> PATH」的顺序解析可执行文件。"""
    from_env = os.environ.get(env_var)
    if from_env:
        if not Path(from_env).exists():
            sys.exit("[错误] %s=%s 指向的路径不存在：%s" % (env_var, from_env, from_env))
        return from_env
    for p in defaults:
        if Path(p).exists():
            return p
    found = shutil.which(name)
    if found:
        return found
    return None


def run_mysql(mysql, sql, database=DB_NAME):
    """跑一条 SQL。返回 (returncode, stdout, stderr)。

    ⚠️ 口令走 MYSQL_PWD 环境变量，不进 argv —— 旧 PowerShell 版用
    `-p"$env:MYSQL_PASSWORD"`，会让明文口令出现在进程命令行里（`tasklist`/进程管理器可见）。
    """
    env = dict(os.environ)
    env.setdefault("MYSQL_PWD", os.environ.get("MYSQL_PASSWORD", ""))
    cmd = [mysql,
           "--host=" + DB_HOST,
           "--port=" + DB_PORT,
           "--user=" + DB_USER,
           "--default-character-set=utf8mb4",
           "--batch", "--skip-column-names"]
    if database:
        cmd.append("--database=" + database)
    cmd += ["--execute=" + sql]
    p = subprocess.run(cmd, capture_output=True, text=True, env=env, encoding="utf-8",
                       errors="replace")
    return p.returncode, p.stdout.strip(), p.stderr.strip()


def run_redis(redis_cli, args):
    p = subprocess.run([redis_cli] + args, capture_output=True, text=True,
                       encoding="utf-8", errors="replace")
    return p.returncode, p.stdout.strip(), p.stderr.strip()


# ---------------------------------------------------------------------------
# 种子基线解析
# ---------------------------------------------------------------------------

def split_top_level_tuples(values_text):
    """把 `VALUES` 后面那段文本切成顶层元组列表。

    需要处理字符串里的括号与转义，所以不能直接用正则 `(...)`：
    种子数据里存在 `'说明文字（括号）'` 这类内容。
    """
    tuples, depth, start, in_str, quote, esc = [], 0, None, False, "", False
    for i, ch in enumerate(values_text):
        if in_str:
            if esc:
                esc = False
            elif ch == "\\":
                esc = True
            elif ch == quote:
                in_str = False
            continue
        if ch in ("'", '"'):
            in_str, quote = True, ch
            continue
        if ch == "(":
            if depth == 0:
                start = i
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0 and start is not None:
                tuples.append(values_text[start + 1:i])
                start = None
    return tuples


def parse_seed_max_ids(repo_root):
    """从种子 SQL 里解析出每张表的种子 id 上界。

    做法：找到每个 `INSERT INTO \\`table\\` ... VALUES (...)` 语句，
    取每条元组的**第一个整数字段**（这些表的自增主键 `id` 都排在第一列），
    取最大值。

    返回 {table: max_seed_id}。
    """
    sql_dir = repo_root / "backend" / "sql"
    if not sql_dir.is_dir():
        return {}

    result = {}
    for name in SEED_SQL_FILES:
        f = sql_dir / name
        if not f.is_file():
            continue
        text = f.read_text(encoding="utf-8", errors="replace")
        for m in re.finditer(r"INSERT\s+INTO\s+`(\w+)`", text, re.IGNORECASE):
            table = m.group(1)
            # 从 VALUES 关键字之后开始扫
            vpos = text.upper().find("VALUES", m.end())
            if vpos == -1:
                continue
            # 找到这条 INSERT 语句的结尾（下一个分号且不在括号/字符串内）
            end = vpos
            depth, in_str, quote, esc = 0, False, "", False
            while end < len(text):
                ch = text[end]
                if in_str:
                    if esc:
                        esc = False
                    elif ch == "\\":
                        esc = True
                    elif ch == quote:
                        in_str = False
                elif ch in ("'", '"'):
                    in_str, quote = True, ch
                elif ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
                elif ch == ";" and depth == 0:
                    break
                end += 1
            segment = text[vpos + len("VALUES"):end]
            for tup in split_top_level_tuples(segment):
                first = tup.split(",", 1)[0].strip().rstrip(",")
                if first.isdigit():
                    val = int(first)
                    if val > result.get(table, 0):
                        result[table] = val
    return result


# ---------------------------------------------------------------------------
# 计划
# ---------------------------------------------------------------------------

def build_plan(mysql, baselines, wanted):
    rc, out, err = run_mysql(mysql, "SELECT table_name FROM information_schema.tables "
                                    "WHERE table_schema='%s';" % DB_NAME, database=None)
    if rc != 0:
        sys.exit("[错误] 读取表清单失败：%s" % err)
    existing = {line.strip() for line in out.splitlines() if line.strip()}

    candidates = wanted or CANDIDATE_TABLES
    plan, skipped = [], []

    for table in candidates:
        if table not in existing:
            skipped.append((table, "表不存在（已剔除）"))
            continue
        if table not in baselines:
            skipped.append((table, "无权威基线（解析不出种子 id 上界）→ 不猜、不删"))
            continue

        baseline = baselines[table]
        rc, cnt, err = run_mysql(mysql, "SELECT COUNT(*) FROM `%s`;" % table)
        if rc != 0:
            sys.exit("[错误] 统计 %s 失败：%s" % (table, err))
        current = int(cnt or 0)

        rc, mx, err = run_mysql(mysql, "SELECT IFNULL(MAX(id),0) FROM `%s`;" % table)
        if rc != 0:
            sys.exit("[错误] 读取 %s 的 MAX(id) 失败：%s" % (table, err))
        current_max = int(mx or 0)

        to_delete = current - run_count(mysql, table, baseline)
        plan.append({
            "table": table,
            "baseline": baseline,
            "current": current,
            "current_max": current_max,
            "to_delete": to_delete,
        })
    return plan, skipped


def run_count(mysql, table, baseline):
    rc, out, err = run_mysql(mysql, "SELECT COUNT(*) FROM `%s` WHERE id <= %d;"
                             % (table, baseline))
    if rc != 0:
        return 0
    return int(out or 0)


# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------

def print_plan(plan, skipped, source):
    print("== 恢复基线 · 计划 ==")
    print("数据库: %s:%s / %s" % (DB_HOST, DB_PORT, DB_NAME))
    print("基线来源: %s" % source)
    print("动作: DELETE FROM <table> WHERE id > <基线>")
    print("")
    print("%-26s %12s %12s %12s  %s" % ("表", "基线id", "当前MAX", "待删行", "状态"))
    print("-" * 86)
    for p in plan:
        print("%-26s %12d %12d %12d  %s" % (
            p["table"], p["baseline"], p["current_max"], p["to_delete"],
            "将删除" if p["to_delete"] > 0 else "无变化"))
    for table, why in skipped:
        print("%-26s %12s %12s %12s  ⏭ %s" % (table, "-", "-", "-", why))
    print("")


def print_summary(ok, failed, skipped_total):
    print("")
    print("== 汇总 ==")
    print("  成功 %d 张 / 失败 %d 张 / 跳过 %d 张" % (len(ok), len(failed), skipped_total))
    if failed:
        print("")
        print("  失败明细：")
        for table, err in failed:
            print("    ❌ %s —— %s" % (table, err))
    if not failed:
        print("  ✅ 全部成功")
    print("")
    print("建议接着跑一遍：")
    print('    cd frontend && pnpm exec playwright test --grep "数据回滚"')


# ---------------------------------------------------------------------------
# 执行
# ---------------------------------------------------------------------------

def execute_plan(mysql, plan, force):
    ok, failed = [], []
    print("")
    print("== 执行 ==")
    for p in plan:
        if p["to_delete"] <= 0:
            print("  %-26s 无变化，跳过" % p["table"])
            ok.append(p["table"])
            continue

        if not force and (p["to_delete"] > DELETE_WARN_ABS or
                          p["to_delete"] > max(1, int(p["current"] * DELETE_WARN_RATIO))):
            failed.append((p["table"],
                           "待删 %d 行（占全表 %.0f%%）超过阈值，疑似基线解析错误；"
                           "确认无误请加 --force" % (
                               p["to_delete"],
                               100.0 * p["to_delete"] / max(1, p["current"]))))
            print("  %-26s ⛔ 已拦截：%s" % (p["table"], failed[-1][1]))
            continue

        sql = "DELETE FROM `%s` WHERE id > %d;" % (p["table"], p["baseline"])
        rc, out, err = run_mysql(mysql, sql)
        if rc != 0:
            # ⚠️ 旧 PowerShell 版在这里 2>$null 把错误吞掉，affected 变空串，
            #    脚本照样打印「✅ 完成」并 exit 0 —— 这是最坏的一种失败。
            #    本版把 stderr 原样带出来，并让退出码非 0。
            failed.append((p["table"], err or ("mysql 退出码 %d" % rc)))
            print("  %-26s ❌ %s" % (p["table"], failed[-1][1]))
            continue

        rc2, cnt2, _ = run_mysql(mysql, "SELECT COUNT(*) FROM `%s`;" % p["table"])
        after = int(cnt2 or 0)
        ok.append(p["table"])
        print("  %-26s 删除 %d 行：%d → %d" % (p["table"], p["to_delete"], p["current"], after))
        print("      SQL: %s" % sql)
    return ok, failed


def clean_redis(redis_cli, assume_yes):
    print("")
    print("== Redis 清理 ==")
    if redis_cli is None:
        print("  ⏭ 未找到 redis-cli，跳过（如需清理请设 REDIS_CLI 环境变量）")
        return True
    all_ok = True
    for pattern in REDIS_PATTERNS:
        rc, out, err = run_redis(redis_cli, ["--scan", "--pattern", pattern])
        if rc != 0:
            print("  ❌ %s 扫描失败：%s" % (pattern, err))
            all_ok = False
            continue
        keys = [k for k in out.splitlines() if k.strip()]
        if not keys:
            print("  %-14s （无 key）" % pattern)
            continue
        if not assume_yes:
            print("  %-14s 命中 %d 个 key：%s" % (pattern, len(keys), ", ".join(keys[:5])))
            ans = input("     确认删除？(yes 继续) ").strip()
            if ans != "yes":
                print("     已取消 %s" % pattern)
                continue
        for k in keys:
            rc2, _, err2 = run_redis(redis_cli, ["DEL", k])
            if rc2 != 0:
                print("  ❌ DEL %s 失败：%s" % (k, err2))
                all_ok = False
        print("  %-14s ✅ 已删除 %d 个 key" % (pattern, len(keys)))
    return all_ok


# ---------------------------------------------------------------------------
# main
# ---------------------------------------------------------------------------

def main():
    p = argparse.ArgumentParser(
        description="把 nianglin 库与 Redis 恢复到干净种子基线（破坏性，默认 dry-run）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--execute", action="store_true",
                   help="真正执行删除（不加则只打印计划）")
    p.add_argument("--yes", action="store_true",
                   help="跳过二次确认（CI / 自动化场景）")
    p.add_argument("--force", action="store_true",
                   help="单表待删行数超阈值时仍然执行（用于确认基线无误后放行）")
    p.add_argument("--skip-redis", action="store_true", help="不碰 Redis")
    p.add_argument("--tables", default="",
                   help="逗号分隔，只处理这些表（默认全部候选表）")
    args = p.parse_args()

    if not os.environ.get("MYSQL_PASSWORD"):
        sys.exit("[错误] 未设置 MYSQL_PASSWORD（本机 dev 口令）。\n"
                 "        脚本不内置默认口令，请先设置再重试。")

    repo_root = Path(__file__).resolve().parents[2]
    mysql = find_binary("MYSQL_BIN", MYSQL_DEFAULT_PATHS, "mysql")
    if mysql is None:
        sys.exit("[错误] 找不到 mysql 客户端，请设 MYSQL_BIN 环境变量指定。")
    redis_cli = None if args.skip_redis else find_binary("REDIS_CLI", REDIS_DEFAULT_PATHS, "redis-cli")

    # 基线：手工确认的优先，其余从种子 SQL 解析
    manual = {t: v for t, v in SEED_MAX_IDS.items()}
    parsed = parse_seed_max_ids(repo_root)
    baselines = dict(parsed)
    baselines.update(manual)
    source = "SEED_MAX_IDS 手工确认 %d 项 + 种子 SQL 解析 %d 项" % (len(manual), len(parsed))

    wanted = [t.strip() for t in args.tables.split(",") if t.strip()] or None
    plan, skipped = build_plan(mysql, baselines, wanted)
    print_plan(plan, skipped, source)

    if not args.execute:
        print("⚠️ 当前是 dry-run 模式，**未执行任何 SQL**。")
        print("   确认无误后请加 --execute 再次运行。")
        if skipped:
            print("")
            print("注意：%d 张表被跳过（见上表 ⏭ 行）。跳过的表**不会**被清理 ——" % len(skipped))
            print("      旧版会用「删 id 最大的 100 行」去猜基线，那会删掉真实种子数据。")
        return 0

    print("")
    print("即将 DELETE 多张表的写路径新增行，此操作不可逆。")
    if not args.yes:
        ans = input("确认继续？(输入 yes 继续，其它任意键退出) ").strip()
        if ans != "yes":
            print("已取消。")
            return 1

    ok, failed = execute_plan(mysql, plan, args.force)

    redis_ok = True
    if not args.skip_redis:
        redis_ok = clean_redis(redis_cli, args.yes)

    print_summary(ok, failed, len(skipped))
    if failed or not redis_ok:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
