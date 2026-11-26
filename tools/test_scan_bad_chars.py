# -*- coding: utf-8 -*-
"""`tools/scan_bad_chars.py` 的回归测试（纯标准库，不连库、不联网）。

存在的理由：这个脚本曾打印「共 6 处」却仍 `exit 0`，CI 也没有接它 ——
于是仓库里 6 处坏字符（含一处韩文）从 2026-11 一路躺到被两轴评审翻出来。
本测试锁住三件事：

1. **命中必须 exit 1**（CI 判据靠它；只打印不算检查）
2. **两类规则各自生效**：U+FFFD 与「非中英文字符」
3. **显式豁免真的生效**：`bad-chars-allow` 只跳该行，不跳整个文件

用法：

    python -m unittest tools.test_scan_bad_chars
    python tools/test_scan_bad_chars.py          # 等价
"""

from __future__ import annotations

import io
import os
import shutil
import tempfile
import unittest

import sys

sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), "..")))

from tools import scan_bad_chars as scanner  # noqa: E402


class _TempDirCase(unittest.TestCase):
    """每个用例一个临时目录，避免互相污染。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="badchars-")

    def tearDown(self):
        shutil.rmtree(self.tmp, ignore_errors=True)

    def write(self, name, text):
        path = os.path.join(self.tmp, name)
        os.makedirs(os.path.dirname(path), exist_ok=True) if os.path.dirname(path) else None
        with io.open(path, "w", encoding="utf-8", newline="") as handle:
            handle.write(text)
        return path


class ReplacementCharTest(_TempDirCase):
    """规则 1：U+FFFD 替换字符。"""

    def test_scanFileShouldReportReplacementCharWithLineNumber(self):
        path = self.write("A.java", "第一行正常\n第三行含坏字\uFFFD\n")

        hits = scanner.scan_file(path)

        self.assertEqual(1, len(hits))
        lineno, reason, _line = hits[0]
        self.assertEqual(2, lineno)
        self.assertIn("U+FFFD", reason)

    def test_scanFileShouldIgnoreCleanFile(self):
        path = self.write("B.md", "# 标题\n随堂笔记，全是正常中文与 ASCII。\n")

        self.assertEqual([], scanner.scan_file(path))

    def test_mainShouldExitOneWhenReplacementCharFound(self):
        self.write("C.sql", "SELECT '\uFFFD';\n")

        # 走 `<目录>` 分支（全量走查），不依赖 git
        code = scanner.main([self.tmp])

        self.assertEqual(1, code)

    def test_mainShouldExitZeroOnCleanTree(self):
        self.write("D.yml", "key: value\n")

        self.assertEqual(0, scanner.main([self.tmp]))


class ForeignScriptTest(_TempDirCase):
    """规则 2：混进来的韩文 / 假名 / 西里尔字母。"""

    def test_scanFileShouldReportHangul(self):
        # 实测复现：一处注释里混进了韩文（U+BA85，码字）。
        # ⚠️ 这里用码字而不是字面量 —— 测试文件本身也要能被本脚本扫绿。
        path = self.write("E.py", "# 拦截器对外说的话本来就要点\uba85违禁表述\n")

        hits = scanner.scan_file(path)

        self.assertEqual(1, len(hits))
        self.assertIn("非中英文字符", hits[0][1])

    def test_scanFileShouldReportKana(self):
        path = self.write("F.vue", "// \u30c6\u30b9\u30c8\n")

        self.assertEqual(1, len(scanner.scan_file(path)))

    def test_scanFileShouldReportCyrillic(self):
        path = self.write("G.md", "注释里混进 \u0434\u0430 两个西里尔字母\n")

        self.assertEqual(1, len(scanner.scan_file(path)))

    def test_scanFileShouldAllowFullWidthPunctuationAndArrows(self):
        # 全角标点 / 箭号 / 框线不是「外文」，不能误伤
        path = self.write("H.md", "「引号」《书名》→ ← ✅ ⚠️ ─── ┌─┐ ① ②\n")

        self.assertEqual([], scanner.scan_file(path))


class AllowMarkerTest(_TempDirCase):
    """显式逐行豁免：只跳该行，不跳整个文件。"""

    def test_scanFileShouldSkipLineWithAllowMarker(self):
        path = self.write("I.md", "坏字\uFFFD # bad-chars-allow 用户粘贴的原样日志\n")

        self.assertEqual([], scanner.scan_file(path))

    def test_scanFileShouldStillReportOtherLinesInSameFile(self):
        path = self.write(
            "J.md",
            "坏字\uFFFD # bad-chars-allow 这一行豁免\n第二行没豁免\uFFFD\n",
        )

        hits = scanner.scan_file(path)

        self.assertEqual(1, len(hits))
        self.assertEqual(2, hits[0][0])


class SuffixFilterTest(_TempDirCase):
    """只读文本后缀：二进制与未知类型不进来。"""

    def test_scanShouldSkipBinarySuffix(self):
        # .docx 不是文本，即便字节流里恰好出现 U+FFFD 也不该报
        path = self.write("K.docx", "坏字\uFFFD\n")

        self.assertEqual([], scanner.scan([path]))

    def test_scanShouldCoverBackendFrontendDocsAndScripts(self):
        # 五类各取一个后缀，确保不会被某个扩展名静默漏掉
        for name in ("A.java", "B.vue", "C.md", "D.py", "E.yml"):
            path = self.write(name, "坏字\uFFFD\n")
            self.assertEqual(1, len(scanner.scan([path])), "%s 未被扫描" % name)


class RepoTreeTest(unittest.TestCase):
    """仓库自身必须是干净的 —— 这就是 CI 里那一步。"""

    def test_repoShouldHaveNoBadChars(self):
        reports = scanner.scan(scanner._iter_repo_files(scanner.ROOT))

        self.assertEqual([], reports, "仓库里有坏字符：\n" + "\n".join(reports))


if __name__ == "__main__":
    unittest.main(verbosity=2)
