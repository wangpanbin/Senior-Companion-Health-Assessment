"""造 10000 条 BENCH 订单用于 M10 overview 压测。"""
import os
import subprocess
import sys
import time

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

MYSQL = r"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe"

INSERT_SQL = """
INSERT INTO companion_order
    (order_no, family_id, elder_id, companion_id, status,
     hospital, department, visit_time, address,
     longitude, latitude, fee, actual_fee,
     accept_time, start_time, finish_time, create_time, update_time,
     deleted, version)
WITH RECURSIVE seq AS (
    SELECT 1 AS n UNION ALL SELECT n+1 FROM seq WHERE n < 10000
)
SELECT
    CONCAT('BENCH', LPAD(n, 6, '0')),
    101,
    401,
    CASE WHEN n % 10 = 0 THEN NULL ELSE 300 + (n % 30) END,
    CASE (n % 5)
      WHEN 0 THEN 'PENDING'
      WHEN 1 THEN 'ACCEPTED'
      WHEN 2 THEN 'IN_SERVICE'
      WHEN 3 THEN 'COMPLETED'
      ELSE 'REVIEWED'
    END,
    CONCAT('Test Hospital ', n),
    '心血管内科',
    DATE_ADD('2025-01-01', INTERVAL n HOUR),
    CONCAT('Test Address ', n),
    110.300000 + (n * 0.0001),
    20.000000 + (n * 0.0001),
    100.00 + (n % 50),
    CASE WHEN n % 5 >= 3 THEN 100.00 + (n % 50) ELSE NULL END,
    CASE WHEN n % 5 >= 1 THEN DATE_ADD('2025-01-01', INTERVAL n+1 HOUR) ELSE NULL END,
    CASE WHEN n % 5 >= 2 THEN DATE_ADD('2025-01-01', INTERVAL n+2 HOUR) ELSE NULL END,
    CASE WHEN n % 5 >= 3 THEN DATE_ADD('2025-01-01', INTERVAL n+3 HOUR) ELSE NULL END,
    NOW(),
    NOW(),
    0,
    0
FROM seq;
"""


def mysql_exec(sql: str) -> str:
    return subprocess.run(
        [MYSQL, "--host=127.0.0.1", "--port=3306", "--user=root",
         "--password=" + os.environ["MYSQL_PASSWORD"], "-D", "nianglin",
         "-N", "-B", "-e", sql],
        capture_output=True, text=True, check=True, encoding="utf-8"
    ).stdout.strip()


def main():
    print("=== 造 10000 条 BENCH 订单 ===")
    # Step 1: 清理
    print("Step 1: 清理已有 BENCH 订单")
    cnt_before = mysql_exec("SELECT COUNT(*) FROM companion_order WHERE order_no LIKE 'BENCH%'")
    print(f"  现有 BENCH 订单: {cnt_before} 条")
    if int(cnt_before) > 0:
        mysql_exec("DELETE FROM companion_order WHERE order_no LIKE 'BENCH%'")
        print("  已清理")

    # Step 2: 插入（SET + INSERT 必须同一次 mysql 调用）
    print()
    print("Step 2: 批量插入 10000 条 BENCH 订单（cte_max_recursion_depth=20000）")
    t0 = time.time()
    full_sql = "SET SESSION cte_max_recursion_depth = 20000;\n" + INSERT_SQL
    mysql_exec(full_sql)
    elapsed = time.time() - t0

    cnt_after = mysql_exec("SELECT COUNT(*) FROM companion_order WHERE order_no LIKE 'BENCH%'")
    total = mysql_exec("SELECT COUNT(*) FROM companion_order")
    print(f"  插入耗时: {elapsed:.2f}s")
    print(f"  BENCH 订单: {cnt_after} 条")
    print(f"  数据库订单总数: {total} 条")


if __name__ == "__main__":
    main()