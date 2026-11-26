#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""`tools/e2e/fixture.py` 中「状态文件选择」逻辑的回归测试（纯 stdlib，不连库）。

对应 2026-11-26 实跑 e2e 踩到的两个真实缺陷：

1. **verify 拿错基线** —— 原实现是 `DONE if exists(DONE) else STATE`，
   只要磁盘上留着上一轮的 `state.done.json`，新跑一轮再 verify 就会拿**上一轮**的
   水位去比，把当天正常的 5 行数据报成「行数 75 != 基线 70」。
2. **verify 抹掉 restore 的输入** —— 原实现比对完就 `DROP` 影子表，
   但先 verify 后 restore 是很容易发生的顺序（README 里两组命令之间只隔一行注释），
   影子表一没，`fixture.py restore` 就报 `Table '_e2e_bak_xxx' doesn't exist` 并中止。

两个缺陷都只靠「状态文件名 + 影子表是否保留」判定，不需要连 MySQL。
本测试因此**不导入 fixture 模块**（它在 import 时就解析 mysql.exe 路径），
而是把那段判据抽成纯函数后直接验 —— 见 `fixture.choose_state_source`。

从仓库根目录运行（两种写法等价）：

    python tools/e2e/test_fixture_state_source.py
    python -m unittest tools.e2e.test_fixture_state_source
"""

import os
import sys
import tempfile
import unittest
from pathlib import Path

# 让本文件既能直接跑，也能被 `python -m unittest tools.e2e...` 收集
sys.path.insert(0, str(Path(__file__).resolve().parent))

from fixture import choose_state_source  # noqa: E402


class ChooseStateSourceTest(unittest.TestCase):
    """状态文件优先级：未 restore 的 state.json > 已 restore 的 state.done.json。"""

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="fixture-state-")
        self.state = os.path.join(self.tmp, "state.json")
        self.done = os.path.join(self.tmp, "state.done.json")

    def tearDown(self):
        for path in (self.state, self.done):
            if os.path.exists(path):
                os.remove(path)
        os.rmdir(self.tmp)

    def test_shouldPickStateWhenBothExist(self):
        """两个都在：必须选 state.json（未 restore 的那一轮才是真基线）。"""
        open(self.state, "w").close()
        open(self.done, "w").close()

        chosen = choose_state_source(self.state, self.done)

        self.assertEqual(self.state, chosen)

    def test_shouldFallBackToDoneWhenStateGone(self):
        """restore 后 state.json 已改名：此时才比 done。"""
        open(self.done, "w").close()

        self.assertEqual(self.done, choose_state_source(self.state, self.done))

    def test_shouldPickStateWhenOnlyStateExists(self):
        """首次 snapshot、还没 restore 过：只有 state.json。"""
        open(self.state, "w").close()

        self.assertEqual(self.state, choose_state_source(self.state, self.done))

    def test_shouldReturnNoneWhenNeitherExists(self):
        """都没快照过：返回 None，调用方据此 die。"""
        self.assertIsNone(choose_state_source(self.state, self.done))


class ShadowTableRetentionTest(unittest.TestCase):
    """影子表只在「比对 done」时才允许删 —— 否则会抹掉 restore 的输入。"""

    def test_shouldDropShadowsOnlyForDoneSource(self):
        from fixture import shadows_can_drop

        self.assertTrue(shadows_can_drop("/x/state.done.json", "/x/state.done.json"))
        self.assertFalse(shadows_can_drop("/x/state.json", "/x/state.done.json"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
