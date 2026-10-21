"""意图分类测试。

分类器是**纯规则**的，所以每条规则都能被逐条锁死 —— 这正是选它
而不选自主 Agent 的原因（ADR-0020 决策 4）。
"""

from __future__ import annotations

import pytest

from app import intents


@pytest.mark.parametrize(
    "question",
    [
        "我的订单到哪了",
        "订单状态是什么",
        "接单了吗",
        "谁接单了",
        "什么时候来",
        "完成了没",
        "还能催单吗",
        "多久能接单",
    ],
)
def test_order_status_detected(question: str) -> None:
    assert intents.classify_order_status(question) is True


@pytest.mark.parametrize(
    "question",
    [
        "帮我找个陪诊员",
        "推荐个合适的陪诊员",
        "安排一个陪诊师",
        "谁合适",
        "找谁陪我去医院",
        "陪诊员推荐",
    ],
)
def test_recommend_detected(question: str) -> None:
    assert intents.classify_recommend(question) is True


@pytest.mark.parametrize(
    "question",
    [
        "陪诊费多少钱",
        "下了单还能取消吗",
        "评价在哪里提交",
    ],
)
def test_platform_questions_not_misrouted(question: str) -> None:
    """平台规则类问题**不得**被误判成订单状态或推荐。

    误判的后果是答非所问 —— 比「没听懂」更糟，因为它看起来像个答案。
    """
    assert intents.classify_order_status(question) is False
    assert intents.classify_recommend(question) is False


def test_empty_question_is_safe() -> None:
    assert intents.classify_order_status("") is False
    assert intents.classify_recommend("") is False


def test_match_keywords_returns_the_concrete_hit() -> None:
    """返回的是命中的那个词，而不是布尔值 —— 便于排障。"""
    assert intents.match_keywords("我的订单到哪了", intents.ORDER_STATUS_KEYWORDS) == "我的订单"
    assert intents.match_keywords("陪诊费多少钱", intents.ORDER_STATUS_KEYWORDS) is None
