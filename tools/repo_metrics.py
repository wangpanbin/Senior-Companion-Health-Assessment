# -*- coding: utf-8 -*-
"""
银龄伴诊 · 仓库规模指标一键取数
================================

存在的理由：提交数 / 文件数 / 测试数这类「实测数字」曾散落在 ci.yml 注释、
presentation 各章、TEST_REPORT 等多处散文里，每处都是手工复制的一份缓存，
漂移只能靠验收官人肉发现（2026-10-25 验收 P1-1 / P1-3 的共同根因）。
本脚本把口径收敛成一个入口：**引用规模数字前先跑它，写下来的数字都注明
「取数日期 + 基线」，别再手抄上一份文档。**

口径与 2026-10-25 验收报告 §2.2 一致，各计数规则的复现验证：
  · 提交数        git rev-list --count <base>..HEAD（--no-merges / --merges 拆分）
  · 变更文件      git diff --name-only <base>..HEAD 计数；行数取 --shortstat
  · 后端文件      backend/src/main/java 下 .java 文件数
  · 端点数        @GetMapping/@PostMapping/@PutMapping/@DeleteMapping/@PatchMapping 计数
  · Controller    controller/ 包内的 @RestController（包外另有 3 个 @RestController，
                  不属于「22 个 Controller」口径，别算进来）
  · 表数          backend/sql/V*.sql 的 CREATE TABLE 计数（Flyway 唯一真源）
  · @Test 等      源码注解静态计数（@Test 1031 / @ParameterizedTest 73 / @SpringBootTest 26）
  · surefire 实跑 backend/target/surefire-reports/TEST-*.xml 的 tests 属性求和
                  —— 需要 mvn verify 跑过才有；没有则标注 [跳过]，别用静态注解数冒充实跑数
  · 前端路由      frontend/src/router/routes.js 的 path: 计数
  · 前端单测      frontend/src 下 *.test.js / *.spec.js 文件数
  · E2E           specs/ 下 .spec.js 文件数（静态）；用例实跑数来自
                  `pnpm exec playwright test --list`（会把数据驱动用例展开，
                  静态 test( 计数只有 186，别拿它冒充 254）

纯标准库。只读，不改任何文件。
"""

from __future__ import annotations

import argparse
import glob
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

ROOT = Path(__file__).resolve().parent.parent
BACKEND_MAIN = ROOT / "backend" / "src" / "main" / "java"
BACKEND_TEST = ROOT / "backend" / "src" / "test" / "java"
SQL_DIR = ROOT / "backend" / "sql"
ROUTES_JS = ROOT / "frontend" / "src" / "router" / "routes.js"
SPECS_DIR = ROOT / "frontend" / "e2e" / "specs"
SUREFIRE_DIR = ROOT / "backend" / "target" / "surefire-reports"

MAPPING_RE = re.compile(r"@(?:Get|Post|Put|Delete|Patch)Mapping")


def run_git(*args: str) -> str:
    proc = subprocess.run(
        ["git", *args], capture_output=True, text=True, encoding="utf-8", errors="replace",
        cwd=ROOT,
    )
    if proc.returncode != 0:
        raise RuntimeError("git %s 失败：%s" % (" ".join(args), proc.stderr.strip()[:200]))
    return proc.stdout.strip()


def rev_count(*extra: str) -> int:
    return int(run_git("rev-list", "--count", *extra))


def count_in_files(directory: Path, pattern: str, glob_pattern="*.java") -> int:
    """递归计数 directory 下匹配 pattern 的行数。"""
    total = 0
    rx = re.compile(pattern)
    for path in directory.rglob(glob_pattern):
        with open(path, encoding="utf-8", errors="replace") as f:
            for line in f:
                if rx.search(line):
                    total += 1
    return total


def count_controllers() -> int:
    """controller/ 包内的 @RestController 文件数（路径分隔符无关）。"""
    n = 0
    for path in BACKEND_MAIN.rglob("*.java"):
        if "controller" not in path.parts:
            continue
        with open(path, encoding="utf-8", errors="replace") as f:
            if re.search(r"@RestController", f.read()):
                n += 1
    return n


def surefire_total() -> int | None:
    """surefire 实跑用例数：TEST-*.xml 根节点 tests 属性求和；无报告返回 None。"""
    reports = sorted(glob.glob(str(SUREFIRE_DIR / "TEST-*.xml")))
    if not reports:
        return None
    total = 0
    for r in reports:
        try:
            total += int(ET.parse(r).getroot().get("tests", "0"))
        except ET.ParseError:
            continue
    return total


def playwright_list() -> tuple[int, int] | None:
    """`playwright test --list` 的 (用例数, 文件数)；pnpm 不可用返回 None。

    --list 只做静态展开不启动浏览器，但能把数据驱动的用例展开成实跑口径。
    ⚠️ Windows 上 pnpm 是 pnpm.cmd，subprocess 直接传 "pnpm" 找不到可执行文件
    （FileNotFoundError → 被当成「不可用」），必须先 shutil.which 解析全路径。
    """
    pnpm = shutil.which("pnpm")
    if not pnpm:
        return None
    try:
        proc = subprocess.run(
            [pnpm, "exec", "playwright", "test", "--list"],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            cwd=ROOT / "frontend", timeout=120,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(r"Total:\s*(\d+)\s+tests?\s+in\s+(\d+)\s+files?", proc.stdout)
    return (int(m.group(1)), int(m.group(2))) if m else None


def main() -> int:
    ap = argparse.ArgumentParser(description="打印仓库规模指标（只读）")
    ap.add_argument("--base", default="v3.0", help="增量基线 ref（默认 v3.0）")
    args = ap.parse_args()
    rng = f"{args.base}..HEAD"

    print(f"=== 银龄伴诊 · 仓库规模指标（基线 {args.base} vs HEAD）===\n")

    # ---- Git 增量 ----
    total = rev_count(rng)
    nonmerge = rev_count("--no-merges", rng)
    merges = rev_count("--merges", rng)
    changed_files = len(run_git("diff", "--name-only", rng).splitlines())
    shortstat = run_git("diff", "--shortstat", rng)
    ins_del = re.search(r"(\d+) insertions?\(\+\).*?(\d+) deletions?\(-\)", shortstat)
    print("[Git 增量]")
    print(f"  提交            {total}（{nonmerge} 非合并 + {merges} 合并）")
    print(f"  变更文件        {changed_files}")
    if ins_del:
        print(f"  行变更          新增 {ins_del.group(1)}，删除 {ins_del.group(2)}")
    print()

    # ---- 后端 ----
    java_files = sum(1 for _ in BACKEND_MAIN.rglob("*.java"))
    endpoints = count_in_files(BACKEND_MAIN, MAPPING_RE.pattern)
    controllers = count_controllers()
    tables = sum(
        len(re.findall(r"(?i)CREATE\s+TABLE", p.read_text(encoding="utf-8", errors="replace")))
        for p in sorted(SQL_DIR.glob("V*.sql"))
    )
    n_test = count_in_files(BACKEND_TEST, r"^\s*@Test\s*$")
    n_param = count_in_files(BACKEND_TEST, r"@ParameterizedTest")
    n_spring = count_in_files(BACKEND_TEST, r"@SpringBootTest")
    sf = surefire_total()
    print("[后端]")
    print(f"  主代码文件      {java_files}")
    print(f"  端点            {endpoints}")
    print(f"  Controller      {controllers}（controller/ 包内 @RestController）")
    print(f"  表              {tables}（backend/sql/V*.sql 的 CREATE TABLE）")
    print(f"  @Test 方法      {n_test}")
    print(f"  @ParameterizedTest {n_param}（注解总数 {n_test + n_param}）")
    print(f"  @SpringBootTest {n_spring} 处")
    if sf is None:
        print("  surefire 实跑   [跳过] 没有 backend/target/surefire-reports —— 跑一次 mvn verify 再来")
    else:
        print(f"  surefire 实跑   {sf}（参数化展开后的实跑口径，ci.yml/汇报用这套）")
    print()

    # ---- 前端 ----
    routes = len(re.findall(r"\bpath:", ROUTES_JS.read_text(encoding="utf-8")))
    unit_files = sum(
        1 for p in (ROOT / "frontend" / "src").rglob("*")
        if p.suffix == ".js" and (p.name.endswith(".test.js") or p.name.endswith(".spec.js"))
    )
    specs = sum(1 for _ in SPECS_DIR.rglob("*.spec.js"))
    static_tests = count_in_files(SPECS_DIR, r"^\s*test\(", glob_pattern="*.spec.js")
    pw = playwright_list()
    print("[前端]")
    print(f"  路由            {routes}（routes.js 的 path:）")
    print(f"  单测文件        {unit_files}")
    print(f"  E2E spec        {specs}（静态 test( 声明 {static_tests}，数据驱动未展开）")
    if pw is None:
        print("  E2E 用例实跑    [跳过] pnpm/playwright 不可用 —— 需 frontend 依赖已装")
    else:
        print(f"  E2E 用例实跑    {pw[0]}（--list 展开口径，{pw[1]} 个文件）")
    print()

    print("引用以上数字时注明「取数命令 python tools/repo_metrics.py + 基线 ref」；")
    print("surefire / --list 是实跑口径，@Test / test( 是静态口径，三套别混用。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
