"""编排层测试：黄金问答集。

## 为什么核心断言不依赖 LLM

夹具用的是 :class:`~app.llm.NullProvider`（降级路径）。所以本文件里的每一条断言
在**没有网络、没有 key、没有模型**的机器上都能跑 —— 这正是 ADR-0020 决策 4
「降级路径与主路径同构」的验收方式。

接了模型的冒烟测试在 ``test_llm_smoke.py``。
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from app import guard
from app.schemas import ChatRequest
from app.service import AssistantService

GOLDEN_FILE = Path(__file__).parent / "golden_qa.json"

#: 请求体字段（构造 ChatRequest 用）
REQUEST_FIELDS = (
    "question",
    "history",
    "elders",
    "order",
    "candidates",
    "recentOrders",
)

#: 断言字段（不属于请求体）
ASSERTION_FIELDS = ("name", "expectedIntent", "mustContain", "mustNotContain")


def load_cases() -> list[dict]:
    data = json.loads(GOLDEN_FILE.read_text(encoding="utf-8"))
    return data["cases"]


def build_request(case: dict) -> ChatRequest:
    payload = {key: case.get(key) for key in REQUEST_FIELDS if key in case}
    return ChatRequest(**payload)


CASES = load_cases()


@pytest.mark.parametrize("case", CASES, ids=[case["name"] for case in CASES])
def test_golden_case(service: AssistantService, case: dict) -> None:
    """黄金问答集逐条断言。"""
    response = service.chat(build_request(case))

    # 意图
    assert response.intent == case["expectedIntent"], (
        f"意图不符：期望 {case['expectedIntent']}，实际 {response.intent}"
    )

    # 必须包含
    for needle in case.get("mustContain", []):
        assert needle in response.answer, f"回答缺少「{needle}」：{response.answer}"

    # 必须不包含
    for needle in case.get("mustNotContain", []):
        assert needle not in response.answer, f"回答不该出现「{needle}」：{response.answer}"

    # 通用红线：任何回答都不得出现禁用词
    for word in guard.medical_advice_words():
        assert word not in response.answer, f"回答含禁用词「{word}」：{response.answer}"

    # 通用红线：任何回答都不得出现派单措辞
    for word in guard.ASSIGNMENT_FORBIDDEN_WORDS:
        assert word not in response.answer, f"回答含派单措辞「{word}」：{response.answer}"


@pytest.mark.parametrize("case", CASES, ids=[case["name"] for case in CASES])
def test_golden_case_is_degraded_without_llm(service: AssistantService, case: dict) -> None:
    """没接 LLM 时 ``degraded`` 的语义。

    - 医疗拒答：**不经 LLM**（输入护栏直接拦下），所以 ``degraded = False`` ——
      它是**正确路径**，不是「降级」。把拒答标成降级会让前端误显示
      「当前为基础模式」，反而把一条正常的合规回复说成了次品。
    - 其它意图：确实走了降级路径（直接给 FAQ 原文），``degraded = True``。
    """
    response = service.chat(build_request(case))
    if response.intent == "REFUSE_MEDICAL":
        assert response.degraded is False
    else:
        assert response.degraded is True


def test_abusive_question_raises(service: AssistantService) -> None:
    """辱骂 / 引流 → 抛 IllegalQuestion（Java 门面转 10003）。"""
    with pytest.raises(guard.IllegalQuestion):
        service.chat(ChatRequest(question="你是骗子，加我微信私下转账"))


def test_answer_never_contains_assignment_wording(
    service: AssistantService,
) -> None:
    """即使候选数据里混进了派单措辞，输出护栏也要拦下来。"""
    response = service.chat(
        ChatRequest(
            question="推荐个陪诊员",
            candidates=[
                {
                    "companionId": 301,
                    "companionName": "李*军",
                    "totalScore": 86,
                    "reasons": ["已指派给该陪诊员"],
                    "mismatches": [],
                    "notes": [],
                }
            ],
        )
    )
    for word in guard.ASSIGNMENT_FORBIDDEN_WORDS:
        assert word not in response.answer


def test_draft_action_is_produced_for_extractable_info(service: AssistantService) -> None:
    """能从话里抽出就诊信息 → 产出 FILL_ORDER_DRAFT + NAVIGATE。"""
    response = service.chat(
        ChatRequest(
            question="帮我去海南省人民医院心内科约个陪诊员",
            elders=[{"id": 401, "name": "张*"}],
        )
    )
    action_types = [action.type for action in response.actions]
    assert "FILL_ORDER_DRAFT" in action_types
    assert "NAVIGATE" in action_types

    fill = next(a for a in response.actions if a.type == "FILL_ORDER_DRAFT")
    assert fill.payload.get("hospital") == "海南省人民医院"
    assert fill.payload.get("department") == "心内科"
    # 只有一个老人 → 自动带上；前端据此直接跳 step2
    assert fill.payload.get("elderId") == 401
    navigate = next(a for a in response.actions if a.type == "NAVIGATE")
    assert navigate.payload.get("path") == "/family/order/step2"


def test_no_draft_action_when_nothing_extractable(service: AssistantService) -> None:
    """抽不到就诊信息 → **不给空卡片**。"""
    response = service.chat(ChatRequest(question="陪诊费多少钱"))
    assert response.actions == []


def test_multiple_elders_does_not_auto_pick(service: AssistantService) -> None:
    """多个老人时**不自动挑人** —— 猜错人比不填更糟。"""
    response = service.chat(
        ChatRequest(
            question="帮我去海南省人民医院心内科约个陪诊员",
            elders=[{"id": 401, "name": "张*"}, {"id": 402, "name": "李*"}],
        )
    )
    fill = next((a for a in response.actions if a.type == "FILL_ORDER_DRAFT"), None)
    assert fill is not None
    assert "elderId" not in fill.payload
    navigate = next(a for a in response.actions if a.type == "NAVIGATE")
    assert navigate.payload.get("path") == "/family/order/step1"


def test_sources_are_reported(service: AssistantService) -> None:
    """推荐类回答要带依据引用（可溯源）。"""
    response = service.chat(ChatRequest(question="帮我找个陪诊员"))
    assert response.sources
    assert any("03-order.md" in source for source in response.sources)
