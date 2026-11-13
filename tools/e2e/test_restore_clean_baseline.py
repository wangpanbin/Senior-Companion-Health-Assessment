#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""restore_clean_baseline 水位对账的回归测试（纯 stdlib，不连 MySQL / Redis）。

从仓库根目录运行（两种写法等价）：

    python tools/e2e/test_restore_clean_baseline.py
    python -m unittest tools.e2e.test_restore_clean_baseline

导入 restore_clean_baseline 只触发模块级常量与函数定义
（`if __name__ == "__main__"` 守卫在文件末尾），不会连库、不会删数据。
"""

import json
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path

# 让本文件既能直接跑，也能被 `python -m unittest tools.e2e...` 收集
sys.path.insert(0, str(Path(__file__).resolve().parent))

import restore_clean_baseline as rcb  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]


class ResolveBaselinesTest(unittest.TestCase):
    """resolve_baselines：三层取值、来源标签与对账明细。"""

    def test_fixture_and_manual_take_max(self):
        """影子表 + 手工同时存在 → 取高者（internal_message 40072 vs 40075 → 40075）。"""
        baselines, sources, notes = rcb.resolve_baselines(
            parsed={"internal_message": 40075},
            manual={"internal_message": 40075},
            fixture_wm={"internal_message": 40072},
        )
        self.assertEqual(baselines["internal_message"], 40075)
        self.assertEqual(sources["internal_message"], "影子表+手工")
        self.assertEqual(notes, [])  # 解析 == 生效 → 无对账明细

    def test_internal_message_manual_lifts_over_fixture(self):
        """修复后 internal_message 经手工值定案 40075（影子表仍停在 40072）。"""
        parsed = rcb.parse_seed_max_ids(REPO_ROOT)
        self.assertEqual(parsed.get("internal_message"), 40075)  # V3 补 40073-40075
        baselines, sources, _ = rcb.resolve_baselines(
            parsed, rcb.SEED_MAX_IDS, {"internal_message": 40072},
        )
        self.assertEqual(baselines["internal_message"], 40075)
        self.assertEqual(sources["internal_message"], "影子表+手工")

    def test_manual_wins_when_fixture_absent(self):
        """影子表缺失（干净 clone）→ 手工值直接定案。"""
        baselines, sources, notes = rcb.resolve_baselines(
            parsed={"internal_message": 40075},
            manual={"internal_message": 40075},
            fixture_wm={},
        )
        self.assertEqual(baselines["internal_message"], 40075)
        self.assertEqual(sources["internal_message"], "手工")
        self.assertEqual(notes, [])

    def test_parse_fallback_when_no_truth_source(self):
        """两层真源都缺该表 → 退回解析值，来源标签为「种子SQL解析」。"""
        baselines, sources, notes = rcb.resolve_baselines(
            parsed={"synthetic_table": 123},
            manual={},
            fixture_wm={},
        )
        self.assertEqual(baselines["synthetic_table"], 123)
        self.assertEqual(sources["synthetic_table"], "种子SQL解析")
        self.assertEqual(notes, [])

    def test_parse_above_confirmed_uses_confirmed_with_note(self):
        """解析 > 真源高者 → 按真源取值，且发出「按真源取值」明细。

        internal_message 形态：解析 40075（V3）> 影子表+手工 40072（72 行旧值）。
        """
        baselines, sources, notes = rcb.resolve_baselines(
            parsed={"internal_message": 40075},
            manual={"internal_message": 40072},
            fixture_wm={"internal_message": 40072},
        )
        self.assertEqual(baselines["internal_message"], 40072)
        self.assertEqual(sources["internal_message"], "影子表+手工")
        self.assertEqual(len(notes), 1)
        self.assertIn("解析 40075 > 生效 40072", notes[0])
        self.assertIn("按真源取值", notes[0])

    def test_parse_below_confirmed_sys_user_and_admin_oper_log(self):
        """解析 < 真源 → 抬升到真源并发出「已抬升」明细（sys_user / admin_oper_log）。"""
        parsed = {"sys_user": 330, "admin_oper_log": 60037}
        manual = {"sys_user": 331, "admin_oper_log": 60038}
        baselines, sources, notes = rcb.resolve_baselines(parsed, manual, {})
        self.assertEqual(baselines["sys_user"], 331)
        self.assertEqual(baselines["admin_oper_log"], 60038)
        self.assertEqual(sources["sys_user"], "手工")
        self.assertEqual(sources["admin_oper_log"], "手工")
        self.assertEqual(len(notes), 2)
        joined = "\n".join(notes)
        self.assertIn("sys_user：解析 330 < 生效 331（手工）→ 已抬升，防误删", joined)
        self.assertIn("admin_oper_log：解析 60037 < 生效 60038（手工）→ 已抬升，防误删", joined)

    def test_sys_login_log_resolves_48572_after_fix(self):
        """P1-B：sys_login_log 手工值 48572（验收报告 §7.2）压过解析 43040。"""
        self.assertEqual(rcb.SEED_MAX_IDS["sys_login_log"], 48572)
        parsed = rcb.parse_seed_max_ids(REPO_ROOT)
        self.assertEqual(parsed.get("sys_login_log"), 43040)  # V2 止于 43040
        baselines, sources, notes = rcb.resolve_baselines(
            parsed, rcb.SEED_MAX_IDS, {},
        )
        self.assertEqual(baselines["sys_login_log"], 48572)
        self.assertEqual(sources["sys_login_log"], "手工")
        self.assertTrue(any("sys_login_log" in n and "已抬升" in n for n in notes))

    def test_synthetic_parse_only_table_keeps_source_label(self):
        """仅解析定案的表来源标签恒为「种子SQL解析」（dry-run ⚠ 行按此判定）。"""
        parsed = {"medicine_dict": 61, "sys_dict": 44045}
        baselines, sources, _ = rcb.resolve_baselines(parsed, rcb.SEED_MAX_IDS, {})
        self.assertEqual(baselines["medicine_dict"], 61)
        self.assertEqual(sources["medicine_dict"], "种子SQL解析")
        self.assertEqual(sources["sys_dict"], "种子SQL解析")

    def test_seed_max_ids_regression_values(self):
        """P1-A / P1-B 定案值的防回归断言。"""
        self.assertEqual(rcb.SEED_MAX_IDS["internal_message"], 40075)
        self.assertEqual(rcb.SEED_MAX_IDS["sys_login_log"], 48572)
        self.assertEqual(rcb.SEED_MAX_IDS["sys_user"], 331)
        self.assertEqual(rcb.SEED_MAX_IDS["admin_oper_log"], 60038)

    def test_fixture_state_files_exclude_pre_run_baseline(self):
        """读取名单只有 state.json / state.done.json —— PRE-RUN 基线永不入名单。"""
        self.assertEqual(rcb.FIXTURE_STATE_FILES, ["state.json", "state.done.json"])
        self.assertNotIn("state.PRE-RUN-BASELINE.json", rcb.FIXTURE_STATE_FILES)


class ParseSeedMaxIdsTest(unittest.TestCase):
    """第三层：种子 SQL 解析（只读 backend/sql/*.sql，不连库）。"""

    def test_parse_real_seed_sql(self):
        parsed = rcb.parse_seed_max_ids(REPO_ROOT)
        self.assertEqual(parsed.get("internal_message"), 40075)  # V2 40072 + V3 40073-75
        self.assertEqual(parsed.get("sys_login_log"), 43040)     # V2 止于 43040
        self.assertEqual(parsed.get("sys_user"), 330)
        self.assertEqual(parsed.get("admin_oper_log"), 60037)


class LoadFixtureWatermarksTest(unittest.TestCase):
    """load_fixture_watermarks：文件缺失 / 损坏 / 合法结构 / PRE-RUN 不读。"""

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.root = Path(self._tmp.name)
        self.fixture_dir = self.root / "reports" / "e2e" / "fixture"
        self.fixture_dir.mkdir(parents=True)

    def _write(self, name, text):
        (self.fixture_dir / name).write_text(text, encoding="utf-8")

    def test_missing_files_return_empty(self):
        self.assertEqual(rcb.load_fixture_watermarks(self.root), {})

    def test_corrupt_json_returns_empty(self):
        self._write("state.json", "{not json")
        self.assertEqual(rcb.load_fixture_watermarks(self.root), {})

    def test_corrupt_state_falls_through_to_done(self):
        self._write("state.json", "{not json")
        self._write("state.done.json", json.dumps(
            {"watermarks": {"sys_user": {"max_id": 331, "count": 93}}}))
        self.assertEqual(rcb.load_fixture_watermarks(self.root), {"sys_user": 331})

    def test_valid_structure_parsed_as_ints(self):
        self._write("state.json", json.dumps({
            "watermarks": {
                "sys_user": {"max_id": 331, "count": 93},
                "internal_message": {"max_id": 40072, "count": 72},
                "bad_row": {"max_id": "331"},
                "bad_row2": 42,
            }
        }))
        self.assertEqual(
            rcb.load_fixture_watermarks(self.root),
            {"sys_user": 331, "internal_message": 40072},
        )

    def test_pre_run_baseline_json_is_never_read(self):
        """state.PRE-RUN-BASELINE.json（污染期水位）刻意不在读取名单。"""
        self._write("state.PRE-RUN-BASELINE.json", json.dumps({
            "watermarks": {
                "sys_user": {"max_id": 5030, "count": 123},
                "internal_message": {"max_id": 40233, "count": 231},
            }
        }))
        result = rcb.load_fixture_watermarks(self.root)
        self.assertEqual(result, {})
        self.assertNotIn("sys_user", result)
        self.assertNotIn("internal_message", result)


class PrintPlanParseOnlyDisplayTest(unittest.TestCase):
    """dry-run 台账的仅解析 ⚠ 提示：只影响输出，不改变删除行为。"""

    def _print(self, plan, sources):
        buf = StringIO()
        with redirect_stdout(buf):
            rcb.print_plan(plan, [], "测试来源", sources)
        return buf.getvalue()

    def test_parse_only_candidate_gets_warning_line(self):
        plan = [{"table": "some_log", "baseline": 10, "current": 20,
                 "current_max": 20, "to_delete": 10}]
        out = self._print(plan, {"some_log": "种子SQL解析"})
        self.assertIn("仅种子 SQL 解析水位", out)
        self.assertIn("⚠ some_log", out)
        self.assertIn("不改变删除行为", out)

    def test_confirmed_candidate_gets_no_warning_line(self):
        plan = [{"table": "sys_user", "baseline": 331, "current": 93,
                 "current_max": 331, "to_delete": 0}]
        out = self._print(plan, {"sys_user": "手工"})
        self.assertNotIn("仅种子 SQL 解析水位", out)
        self.assertNotIn("⚠ sys_user", out)


if __name__ == "__main__":
    unittest.main()
