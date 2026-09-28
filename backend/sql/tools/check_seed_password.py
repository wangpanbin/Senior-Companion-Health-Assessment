#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""T2.4 门禁：种子口令扩散检查。

**为什么需要这个脚本**：2026-09-28 的 T2.4 发现 `Nl@123456` 在仓库里散落
32 处，且分布跨越已执行的 Flyway 脚本、14 个夹具生成器、生产 Service 与
前端登录页。手工清理只能管到当次，下次有人再写一个 `PASSWORD = "Nl@123456"`
又会静默扩散回去 —— 没有任何机制拦住。

本脚本把「允许出现在哪里」写成显式白名单，超出即失败。接法：

- 阶段 5 的 `tools/verify_all.ps1` 会调用它（T2.4 的收尾标准之一）
- 也可单独跑：`python backend/sql/tools/check_seed_password.py`

退出码：0 = 干净；1 = 有新增扩散（退出码即断言，不要只看输出）。
"""

import os
import re
import sys

# --------------------------------------------------------------------------
# 白名单：这些位置出现种子口令是**有意为之**，改动它们需要同步更新本表
# --------------------------------------------------------------------------
# 每条形如 (相对仓库根的路径, 原因)
ALLOWLIST = [
    ("backend/sql/tools/fixture_credentials.py",
     "★ 单一真源：默认值就定义在这里，改口令只改这一处"),
    ("backend/sql/tools/GenSeedSecrets.java",
     "生成 BCrypt 哈希的一次性工具，优先读 NIANGLIN_SEED_PASSWORD"),
    ("backend/sql/tools/check_seed_password.py",
     "本脚本自身——它必须能匹配到目标串才能检出扩散"),
    ("backend/src/main/java/org/company/nianglin/security/SecurityProperties.java",
     "生产配置类的兜底默认值；投产由 DEFAULT_RESET_PASSWORD 覆盖，已在该字段 javadoc 写明"),
    ("backend/src/test/java/org/company/nianglin/service/AdminServiceTest.java",
     "单测固定输入，不参与生产"),
    ("backend/src/test/java/org/company/nianglin/service/AuthServiceTest.java",
     "单测固定输入，不参与生产"),
    ("frontend/.env.development",
     "登录页演示预填；仅 dev 生效（import.meta.env.DEV），不进生产构建"),
    ("frontend/e2e/helpers/accounts.js",
     "E2E 夹具口令，与 fixture_credentials.py 同源；不进生产构建"),
    ("backend/sql/V2__seed_data.sql",
     "⚠️ 已执行的 Flyway 脚本，**不得修改**（validate-on-migrate 校验 checksum）；"
     "改口令需新开 V5__*.sql 走 UPDATE"),
    ("docs/agents/reports/BASELINE_2026-09-28.md",
     "实测报告，如实记录当时的命中分布（§6.1）"),
    ("docs/plan/convergence-2026-09.md",
     "迭代计划，收尾标准里引用了检索串本身"),
]

# 下列文件即使命中也应忽略（非源码，grep 会误报）
SKIP_DIR_PARTS = {"__pycache__", ".git", "node_modules", "target", "dist"}

# 只在这些扩展名里找
SCAN_EXT = {".py", ".java", ".js", ".vue", ".md", ".yml", ".yaml", ".sql", ".properties"}

PATTERN = re.compile(r"Nl@123456")
REPO_ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
SCAN_ROOTS = ["backend", "frontend/src", "frontend/e2e", "docs", "tools", "README.md", "AGENTS.md"]


def is_allowlisted(rel_path: str) -> bool:
    norm = rel_path.replace("\\", "/")
    for allowed, _ in ALLOWLIST:
        if norm.endswith(allowed.replace("\\", "/")):
            return True
    return False


def iter_files():
    for root in SCAN_ROOTS:
        full = os.path.join(REPO_ROOT, root)
        if os.path.isfile(full):
            yield full
            continue
        if not os.path.isdir(full):
            continue
        for dirpath, dirnames, filenames in os.walk(full):
            dirnames[:] = [d for d in dirnames if d not in SKIP_DIR_PARTS]
            for name in filenames:
                if os.path.splitext(name)[1].lower() in SCAN_EXT:
                    yield os.path.join(dirpath, name)


def main() -> int:
    allowed_set = {a.replace("\\", "/") for a, _ in ALLOWLIST}
    violations: list[tuple[str, int]] = []
    allowed_hits: list[tuple[str, int]] = []

    for path in iter_files():
        try:
            with open(path, encoding="utf-8") as fh:
                for lineno, line in enumerate(fh, 1):
                    if not PATTERN.search(line):
                        continue
                    rel = os.path.relpath(path, REPO_ROOT).replace("\\", "/")
                    if is_allowlisted(rel):
                        allowed_hits.append((rel, lineno))
                    else:
                        violations.append((rel, lineno))
        except (UnicodeDecodeError, OSError):
            # 非 UTF-8 文件（如 GBK 命名的中文文件名产物）跳过，不误报
            continue

    print("=" * 72)
    print("T2.4 种子口令扩散检查 · repo root:", REPO_ROOT)
    print("=" * 72)

    if allowed_hits:
        print(f"\n[白名单] {len(allowed_hits)} 处（有意保留，改动需同步 ALLOWLIST）:")
        for rel, lineno in sorted(allowed_hits):
            print(f"  ok  {rel}:{lineno}")

    if violations:
        print(f"\n[违规] {len(violations)} 处白名单外的种子口令:")
        for rel, lineno in sorted(violations):
            print(f"  BAD {rel}:{lineno}")
        print("\n修法：")
        print("  · 夹具脚本   → 改用 backend/sql/tools/fixture_credentials.py 的 PASSWORD")
        print("  · 生产代码   → 改读 SecurityProperties / 环境变量，勿硬编码")
        print("  · 前端演示   → 读 .env.development 的 VITE_DEMO_PASSWORD")
        print("  · 文档       → 指向 fixture_credentials.py，不写明文")
        print("  · 确属白名单 → 加进本脚本的 ALLOWLIST 并写明原因")
        return 1

    print(f"\n[PASS] 白名单外无种子口令（{len(allowed_hits)} 处在白名单内）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
