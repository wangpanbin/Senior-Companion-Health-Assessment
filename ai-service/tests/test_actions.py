"""动作抽取测试。

抽取的是**结构化字段**，所以用确定性断言 —— 同一句话永远抽出同样的结果。
"""

from __future__ import annotations

from datetime import date

from app import actions


def test_extract_hospital() -> None:
    assert actions.extract_hospital("下周三去海南省人民医院心内科") == "海南省人民医院"
    assert actions.extract_hospital("在海口市人民医院复查") == "海口市人民医院"
    assert actions.extract_hospital("帮我找个陪诊员") is None


def test_extract_hospital_stops_at_verbs() -> None:
    """「去」是停止字 —— 不能把「帮我找个陪诊员去」吞进医院名。"""
    result = actions.extract_hospital("帮我找个陪诊员去海南省人民医院")
    assert result == "海南省人民医院"


def test_extract_department() -> None:
    assert actions.extract_department("海南省人民医院心内科") == "心内科"
    assert actions.extract_department("去心血管内科复查") == "心血管内科"
    assert actions.extract_department("神经内科门诊") == "神经内科"


def test_extract_visit_time_with_explicit_date() -> None:
    today = date(2026, 10, 21)
    assert actions.extract_visit_time("2026-10-28 上午9点", today=today) == "2026-10-28 09:00:00"
    assert actions.extract_visit_time("10月28日下午2点半", today=today) == "2026-10-28 14:30:00"


def test_extract_visit_time_with_weekday() -> None:
    """「下周三」→ 下一个周三（2026-10-21 是周三，所以下周三 = 10-28）。"""
    today = date(2026, 10, 21)
    assert actions.extract_visit_time("下周三上午9点", today=today) == "2026-10-28 09:00:00"


def test_extract_visit_time_requires_a_time_too() -> None:
    """只有日期没有时刻 → **抽不到**。猜一个时刻比留空更危险。

    ⚠️ 曾经默认填 ``09:00:00``。那个时刻不是用户说的，是平台编的，
    而它会被前端写进草稿、最终成为订单的就诊时间 ——
    家属看到「下周三 09:00」多半不会意识到这个 09:00 是猜的。
    与 spec §4.11「payload **只带抽到的**」直接冲突。
    """
    today = date(2026, 10, 21)
    assert actions.extract_visit_time("2026-10-28", today=today) is None
    assert actions.extract_visit_time("下周三", today=today) is None


def test_extract_visit_time_requires_a_date() -> None:
    """只有时刻没有日期 → 抽不到。猜一个日期比留空更危险。"""
    today = date(2026, 10, 21)
    assert actions.extract_visit_time("上午9点", today=today) is None


def test_extract_visit_time_returns_none_for_unrelated_text() -> None:
    today = date(2026, 10, 21)
    assert actions.extract_visit_time("陪诊费多少钱", today=today) is None


def test_extract_draft_only_includes_found_fields() -> None:
    """只带抽到的字段 —— 前端只写存在的字段。"""
    extraction = actions.extract_draft("去海南省人民医院心内科", today=date(2026, 10, 21))
    payload = extraction.to_payload()
    assert payload["hospital"] == "海南省人民医院"
    assert payload["department"] == "心内科"
    assert "visitTime" not in payload
    assert "elderId" not in payload


def test_extract_draft_does_not_invent_a_visit_time() -> None:
    """只有日期没有时刻 → 草稿里**不得**出现 visitTime。

    这条锁的是「平台不替用户编时间」—— 草稿里的每个字段都必须是用户说过的。
    """
    extraction = actions.extract_draft(
        "下周三去海南省人民医院心内科", today=date(2026, 10, 21)
    )
    payload = extraction.to_payload()
    assert payload["hospital"] == "海南省人民医院"
    assert payload["department"] == "心内科"
    assert "visitTime" not in payload


def test_extract_draft_with_single_elder() -> None:
    extraction = actions.extract_draft(
        "去海南省人民医院心内科",
        elders=[{"id": 401, "name": "张*"}],
        today=date(2026, 10, 21),
    )
    assert extraction.elder_id == 401
    assert extraction.elder_name == "张*"


def test_extract_draft_with_multiple_elders_leaves_elder_unset() -> None:
    extraction = actions.extract_draft(
        "去海南省人民医院心内科",
        elders=[{"id": 401, "name": "张*"}, {"id": 402, "name": "李*"}],
        today=date(2026, 10, 21),
    )
    assert extraction.elder_id is None


def test_extract_draft_empty() -> None:
    extraction = actions.extract_draft("陪诊费多少钱", today=date(2026, 10, 21))
    assert extraction.is_empty() is True
    assert extraction.to_payload() == {}
