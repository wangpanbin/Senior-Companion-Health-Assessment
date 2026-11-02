# -*- coding: utf-8 -*-
"""银龄伴诊 e2e 脚本公共清理助手（BUG_LIST L1 修复）。

各 e2e 脚本的收尾模式统一为：
    main() 开头记基线（max_id）→ __main__ 的 finally 里调 cleanup() →
    cleanup() 用 delete_since 删「id > 基线」的行 + flush_unread_cache() 清未读缓存。

铁律：基线 <= 0 时**拒绝删除**。`DELETE ... WHERE id > 0` 会把整张种子表清光，
所以宁可漏清一轮测试垃圾，也绝不允许在基线缺失时执行删除。

只用标准库；不 import 各脚本里的 mysql_raw/mysql_value（避免循环依赖与签名漂移），
常量与命令行参数在这里自带一份。
"""
import os
import re
import subprocess

REDIS_CLI = r"D:\develop\Redis-8.8.0\redis-cli.exe"
MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"
DB = "nianglin"

_TABLE_RE = re.compile(r"^[a-z_][a-z0-9_]*$")


def mysql_raw(sql):
    """执行 SQL 但不取值（INSERT / DELETE 用）。"""
    return subprocess.run(
        [MYSQL, "--host=127.0.0.1", "--port=3306", "--user=root",
         "--password=" + os.environ.get("MYSQL_PASSWORD", ""),
         "--batch", "--skip-column-names", "--raw",
         "--default-character-set=utf8mb4", "--database=" + DB, "-e", sql],
        capture_output=True, text=True, encoding="utf-8", errors="replace")


def mysql_value(sql):
    out = mysql_raw(sql)
    return (out.stdout or "").strip()


def max_id(table):
    """记录基线：返回表当前 MAX(id)，空表为 0。表名非法时抛异常（防注入）。"""
    if not _TABLE_RE.match(table):
        raise ValueError("非法表名：%r" % table)
    return int(mysql_value(
        "SELECT COALESCE(MAX(`id`),0) FROM `%s`;" % table) or 0)


def delete_since(table, baseline, label=""):
    """删除 id > baseline 的行。baseline <= 0 时拒绝执行并返回 0（保种子表的铁律）。"""
    if not _TABLE_RE.match(table):
        raise ValueError("非法表名：%r" % table)
    tag = label or table
    if not baseline or baseline <= 0:
        print("[清理] 跳过 %s：基线为 %s（≤0 时删除会清光整表，拒绝执行）"
              % (tag, baseline))
        return 0
    count = int(mysql_value(
        "SELECT COUNT(*) FROM `%s` WHERE `id`>%d;" % (table, baseline)) or 0)
    if count:
        mysql_raw("DELETE FROM `%s` WHERE `id`>%d;" % (table, baseline))
    print("[清理] %s：删除 id>%d 的行 %d 条" % (tag, baseline, count))
    return count


def flush_unread_cache():
    """清掉 message:unread:* 未读数缓存。

    缓存可安全删除——下次读取会按库重算；不清的话缓存里的旧未读数会让
    下一轮 e2e_message 的 H1/H2（未读缓存一致性断言）假性失败。
    """
    out = subprocess.run([REDIS_CLI, "KEYS", "message:unread:*"],
                         capture_output=True, text=True)
    keys = [k for k in (out.stdout or "").splitlines() if k.strip()]
    if keys:
        subprocess.run([REDIS_CLI, "DEL"] + keys, capture_output=True, text=True)
    print("[清理] message:unread:* 缓存键：删除 %d 个" % len(keys))
    return len(keys)
