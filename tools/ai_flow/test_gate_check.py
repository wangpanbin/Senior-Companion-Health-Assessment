#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""gate_check.py「悬空路径」检查的回归测试（纯 stdlib，不连数据库 / 网络）。

从仓库根目录运行（两种写法等价）：

    python tools/ai_flow/test_gate_check.py
    python -m unittest tools.ai_flow.test_gate_check

导入 gate_check 只触发模块级常量与函数定义
（`if __name__ == "__main__"` 守卫在文件末尾），不会改任何文件。

覆盖三条曾经踩过的坑：

1. **被 .gitignore 忽略的文件不算悬空** —— 本地 `.env` 在 CI 的全新检出里
   不存在，但文档引用它是合法的（2026-11-23 实际把 CI 顶红的那次）。
2. **真·不存在的路径仍然要报** —— 不能因为放宽就把门禁放瞎。
3. **Windows 换行不能吃掉匹配** —— `text=True` 时 Python 把 \n 翻成 \r\n，
   git 的 --stdin 会把行尾 \r 当成路径的一部分，规则被静默跳过。
"""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

# 让本文件既能直接跑，也能被 `python -m unittest tools.ai_flow...` 收集
sys.path.insert(0, str(Path(__file__).resolve().parent))

import gate_check as gc  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]

BT = chr(96)


class GitIgnoredPathsTest(unittest.TestCase):
    """git_ignored_paths：规则本体在 .gitignore，脚本不再抄第二份。"""

    def test_ignored_env_file_is_reported_as_ignored(self):
        """ai-service/.env 被 .gitignore 忽略 —— 必须被识别成「忽略」。"""
        ignored = gc.git_ignored_paths(["ai-service/.env"])
        self.assertIn("ai-service/.env", ignored)

    def test_tracked_file_is_not_ignored(self):
        """已入库的文件不算被忽略 —— 否则门禁会把自己的源码当悬空。"""
        ignored = gc.git_ignored_paths(["frontend/package.json"])
        self.assertNotIn("frontend/package.json", ignored)

    def test_empty_input_returns_empty_set(self):
        self.assertEqual(gc.git_ignored_paths([]), set())

    def test_works_when_file_absent_on_disk(self):
        """被忽略的文件在 CI 上根本不存在 —— 判断不能依赖文件是否在磁盘上。"""
        with tempfile.TemporaryDirectory() as tmp:
            # 造一个独立的小仓库，复现 CI 的「全新检出」状态
            subprocess.run(["git", "init", "-q"], cwd=tmp, check=True)
            (Path(tmp) / ".gitignore").write_text("secret.env\n", encoding="utf-8")
            proc = subprocess.run(
                ["git", "check-ignore", "--stdin", "--no-index"],
                cwd=tmp,
                input=b"secret.env\n",
                capture_output=True,
            )
            self.assertEqual(proc.returncode, 0)
            self.assertIn(b"secret.env", proc.stdout)


class CheckMdPathsTest(unittest.TestCase):
    """check_md_paths：悬空路径判定。"""

    def test_ignored_path_is_not_dangling(self):
        """引用被 .gitignore 忽略的文件 → 不算悬空（CI 上它本来就不存在）。"""
        text = "见 " + BT + "ai-service/.env" + BT + " 与 " + BT + "ai-service/.env.example" + BT
        missing = gc.check_md_paths(text)
        self.assertNotIn("ai-service/.env", missing)

    def test_existing_path_is_not_dangling(self):
        """引用真实存在的文件 → 不算悬空。"""
        text = "见 " + BT + "frontend/package.json" + BT
        self.assertNotIn("frontend/package.json", gc.check_md_paths(text))

    def test_missing_path_is_still_dangling(self):
        """引用真·不存在的文件 → 必须报悬空（不能把门禁放瞎）。"""
        text = "见 " + BT + "docs/api/99-does-not-exist.md" + BT
        self.assertIn("docs/api/99-does-not-exist.md", gc.check_md_paths(text))

    def test_non_repo_path_is_skipped(self):
        """非仓库内前缀（如 https 链接）不在检查范围。"""
        text = "见 " + BT + "https://github.com/example/repo" + BT
        self.assertEqual(gc.check_md_paths(text), [])

    def test_duplicate_paths_are_deduped(self):
        """同一路径引用多次 → 只报一次。"""
        text = ("见 " + BT + "docs/api/99-missing.md" + BT + " 与 "
                + BT + "docs/api/99-missing.md" + BT)
        missing = gc.check_md_paths(text)
        self.assertEqual(missing.count("docs/api/99-missing.md"), 1)


class PathCheckExemptTest(unittest.TestCase):
    """path_check_exempt：历史存档豁免「悬空路径」，但圆栏照查。

    判据与 CHANGELOG.md 同一套：**快照类文档**引用当时的路径，
    文件后来被删/改名是文档的性质，不是错误。让快照随今天改名而失效
    等于篡改历史 —— 正确做法是新文档指向现在的位置。
    """

    def test_archive_dir_is_exempt(self):
        self.assertTrue(gc.path_check_exempt(
            "docs/plan/archive/2026-09-28-convergence-iteration.md"))

    def test_review_dir_is_exempt(self):
        self.assertTrue(gc.path_check_exempt("docs/review/2026-09-17-m2-auth.md"))

    def test_changelog_still_exempt(self):
        """旧的单文件豁免不能被目录级改动误伤。"""
        self.assertTrue(gc.path_check_exempt("CHANGELOG.md"))

    def test_normal_doc_is_not_exempt(self):
        """普通文档不豁免 —— 否则门禁就放瞎了。"""
        for rel in ("docs/api/README.md", "AGENTS.md", "docs/plan/README.md"):
            self.assertFalse(gc.path_check_exempt(rel), rel)

    def test_prefix_lookalike_is_not_exempt(self):
        """`docs/reviewsomething/` 不应被 `docs/review/` 前缀咒语命中。"""
        self.assertFalse(gc.path_check_exempt("docs/reviewsomething/x.md"))

    def test_exempt_dir_files_are_skipped_by_check_markdown(self):
        """端到端：存档文件里的悬空路径不进结果，圆栏检查不受影响。"""
        # 用仓库内真实存在的存档文件跑一遍（它在 2026-11-24 确实有悬空路径）
        rel = "docs/plan/archive/2026-09-28-convergence-iteration.md"
        if not (REPO_ROOT / rel).is_file():
            self.skipTest("存档文件已改名，跳过正向断言")
        ok, detail, _ = gc.check_markdown(None, all_md=True)
        self.assertNotIn(rel + " -> ", detail)


class MdSkipDirsTest(unittest.TestCase):
    """all_md_files 的跳过范围：根层级 `reports/` 跳，任意层级构建产物跳。

    2026-11-25 修的缺陷：早先用 `any(seg in MD_SKIP_DIRS for seg in parts[:-1])`
    做**任意层级**匹配，`reports` 把入库的正式报告目录 `docs/reports/`（28 份 .md）
    一起静默跳过了 —— 围栏检查与路径检查双双漏检。
    """

    def test_docs_reports_is_scanned(self):
        """`docs/reports/` 是入库的正式报告目录，必须在扫描范围内。"""
        files = gc.all_md_files()
        hits = [f for f in files if f.startswith("docs/reports/")]
        self.assertTrue(hits, "docs/reports/ 下的 .md 一份都没被扫到 —— 跳过逻辑误伤了")
        self.assertIn("docs/reports/README.md", hits)

    def test_root_reports_is_skipped(self):
        """根 `reports/` 是 Playwright / e2e 产物（.gitignore 已忽略），必须跳过。"""
        files = gc.all_md_files()
        leaked = [f for f in files if f.startswith("reports/")]
        self.assertEqual(leaked, [], "根 reports/ 下的 .md 不该被扫到")

    def test_build_output_dirs_skipped_at_any_depth(self):
        """构建产物目录名在**任意层级**都要跳（node_modules/target/dist 等）。"""
        files = gc.all_md_files()
        for seg in gc.MD_SKIP_ANY_DIRS:
            leaked = [f for f in files if seg in f.split("/")[:-1]]
            self.assertEqual(leaked, [], "%s 下的 .md 不该被扫到" % seg)


if __name__ == "__main__":
    unittest.main(verbosity=2)
