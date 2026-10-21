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
from app.schemas import ChatRequest, HistoryTurn
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
    - 意图不明：**同样不经 LLM**（固定澄清话术），也是正确路径，
      ``degraded = False``。
    - 推荐但**没有候选**：也**不经 LLM**（事实包里没有候选，模型只会硬凑空壳），
      走确定性引导文案（API 文档 §五 已知限制 1），``degraded = False``。
    - 其它意图：确实走了降级路径（直接给 FAQ 原文），``degraded = True``。
    """
    response = service.chat(build_request(case))
    is_deterministic_path = response.intent in ("REFUSE_MEDICAL", "UNKNOWN") or (
        response.intent == "RECOMMEND_COMPANION" and not case.get("candidates")
    )
    if is_deterministic_path:
        assert response.degraded is False
    else:
        assert response.degraded is True


class _ChattyProvider:
    """一个「什么都答得很顺」的假 provider。

    用来证明：**UNKNOWN 与 REFUSE_MEDICAL 一样，模型没有发言权**。
    """

    def narrate(self, facts):  # noqa: ANN001, ANN201 - 协议桩
        return "我可以这样安排：第一步选择医院，第二步选择科室，第三步提交订单。"


def test_unknown_intent_never_uses_the_llm(faq_index) -> None:
    """UNKNOWN → 固定澄清话术，**模型没有发言权**（V3.7 联调回归）。

    背景：接了模型之后，「下周三去海南省人民医院心内科帮我约陪诊」被判成
    UNKNOWN，却拿到一套模型现编的五步下单流程 —— 事实包里根本没有这些步骤，
    且那条回答 ``sources`` 为空。契约写死 UNKNOWN = 返回澄清话术。
    """
    service = AssistantService(faq_index, provider=_ChattyProvider())
    response = service.chat(ChatRequest(question="今天天气怎么样"))

    assert response.intent == "UNKNOWN"
    assert response.answer == guard.CLARIFY_TEMPLATE
    assert "第一步选择医院" not in response.answer
    # 澄清话术是正确路径，不是降级 —— 前端不该显示「基础模式」横幅
    assert response.degraded is False


def test_refuse_medical_never_uses_the_llm(faq_index) -> None:
    """医疗拒答同样不经模型（输入护栏直接拦下）。"""
    service = AssistantService(faq_index, provider=_ChattyProvider())
    response = service.chat(ChatRequest(question="这个药一天吃几次"))

    assert response.intent == "REFUSE_MEDICAL"
    assert response.answer == guard.REFUSAL_TEMPLATE
    assert response.degraded is False


def test_recommend_without_candidates_never_uses_the_llm(faq_index) -> None:
    """没有候选陪诊员 → **不调模型**，走确定性引导（V3.7 联调回归）。

    背景：事实包里没有候选时，模型会硬凑出
    「**命中理由** - 现有事实中未提供具体陪诊员的画像…」这种三段空壳 ——
    看着像三段式结构，其实一句有用信息都没有。

    而 API 文档 §五 已知限制 1 已经把这个场景的行为写死：
    推荐是订单锚定的，没下单本版不支持，助手应引导用户先成单。
    """
    service = AssistantService(faq_index, provider=_ChattyProvider())
    response = service.chat(ChatRequest(question="推荐个陪诊员"))

    assert response.intent == "RECOMMEND_COMPANION"
    assert "第一步选择医院" not in response.answer
    assert "现有事实中未提供" not in response.answer
    # 应引导先成单（降级路径的既有文案）
    assert "推荐陪诊员需要先有就诊信息" in response.answer
    assert response.degraded is False


class _MarkdownProvider:
    """一个爱用 Markdown 的假 provider。"""

    def narrate(self, facts):  # noqa: ANN001, ANN201 - 协议桩
        return "**命中理由**\n- 同服务区域\n\n**吴*华** 匹配度 50"


def test_markdown_markers_are_stripped_from_answers(faq_index) -> None:
    """回答里不许留下 Markdown 标记（前端是纯文本渲染，不解析 Markdown）。

    前端用 ``{{ m.content }}`` 渲染 + ``white-space: pre-wrap``，
    **不解析** Markdown。所以 ``**命中理由**`` 会原样显示给家属。
    """
    service = AssistantService(faq_index, provider=_MarkdownProvider())
    response = service.chat(
        ChatRequest(
            question="推荐个陪诊员",
            candidates=[
                {
                    "companionId": 301,
                    "companionName": "李*军",
                    "totalScore": 86,
                    "reasons": ["同服务区域"],
                    "notes": [],
                }
            ],
        )
    )

    assert "**" not in response.answer
    assert "命中理由" in response.answer
    # 换行分段必须保留（前端靠 pre-wrap 分段）
    assert "\n" in response.answer


def test_normalize_plain_text_keeps_content() -> None:
    """规范化只清标记，不动内容与换行。"""
    from app.service import normalize_plain_text

    assert normalize_plain_text("**标题**\n- 一\n- 二") == "标题\n- 一\n- 二"
    assert normalize_plain_text("## 小标题") == "小标题"
    assert normalize_plain_text("**没闭合的") == "没闭合的"
    assert normalize_plain_text("") == ""


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
    """抽不到就诊信息 → **不给空卡片**。

    ⚠️ 这里**必须带 elders** —— 老人是会话上下文自动补的，
    若把自动补的 elderId 算成「抽到了就诊信息」，
    「陪诊费多少钱」这种纯问规则的提问也会挂出一张「已为你填好下单信息」
    卡片（V3.7 联调实测：真实场景里家属总有老人，卡片因此漏到了每条回答后面）。
    """
    response = service.chat(
        ChatRequest(question="陪诊费多少钱", elders=[{"id": 401, "name": "张*"}])
    )
    assert response.actions == []


def test_no_draft_action_for_unrelated_questions(service: AssistantService) -> None:
    """问订单状态 / 问天气 → 都不该挂下单卡片（哪怕当前家属有绑定老人）。"""
    for question in ("我的订单到哪了", "今天天气怎么样", "下了单还能取消吗"):
        response = service.chat(
            ChatRequest(question=question, elders=[{"id": 401, "name": "张*"}])
        )
        assert response.actions == [], f"「{question}」不该产出草稿动作"


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


# ---------------------------------------------------------------------------
# 历史对话必须被消费（V3.7 收口评审发现）
# ---------------------------------------------------------------------------
#
# 背景：前端把对话存进 sessionStorage 并逐轮发给后端，Java 门面原样转发，
# schema 也接了 ``history`` 字段 —— 但 Python 侧此前**全文零使用**它。
# 端到端「通了」却没有一处消费：家属问「那取消呢」「这个订单呢」，
# 助手拿不到任何上下文，必然答非所问。


class _RecordingProvider:
    """记录收到的 facts 的假 provider。"""

    def __init__(self) -> None:
        self.facts: dict | None = None

    def narrate(self, facts):  # noqa: ANN001, ANN201 - 协议桩
        self.facts = facts
        return "好的，这笔订单的规则我按标准答案说给你听。"


def test_history_is_passed_into_the_facts(faq_index) -> None:
    """历史对话必须进事实包 —— 否则助手没有记忆。"""
    provider = _RecordingProvider()
    service = AssistantService(faq_index, provider=provider)
    service.chat(
        ChatRequest(
            question="那取消呢",
            history=[
                HistoryTurn(role="user", content="陪诊费多少钱"),
                HistoryTurn(role="assistant", content="工作日服务费 128 元。"),
            ],
        )
    )
    assert provider.facts is not None
    facts_text = provider.facts["facts_text"]
    assert "历史对话" in facts_text
    assert "家属：陪诊费多少钱" in facts_text
    assert "助手：工作日服务费 128 元。" in facts_text


def test_history_is_labelled_as_context_not_facts(faq_index) -> None:
    """历史是**上下文**，不是事实 —— 标题必须写明。

    否则模型会把上一轮助手说过的内容当成「事实依据」，
    正好撞上 prompt 约束 2「事实里没有的信息一律不要补充」。
    """
    provider = _RecordingProvider()
    service = AssistantService(faq_index, provider=provider)
    service.chat(
        ChatRequest(
            question="陪诊费多少钱",
            history=[HistoryTurn(role="user", content="上次问过了")],
        )
    )
    assert provider.facts is not None
    assert "仅供理解上下文，不是事实依据" in provider.facts["facts_text"]


def test_standard_answer_stays_after_history(faq_index) -> None:
    """【命中的标准答案】必须留在历史**之后** —— 那是作答时最该看的一段。"""
    provider = _RecordingProvider()
    service = AssistantService(faq_index, provider=provider)
    service.chat(
        ChatRequest(
            question="陪诊费多少钱",
            history=[HistoryTurn(role="user", content="上次问过了")],
        )
    )
    assert provider.facts is not None
    facts_text = provider.facts["facts_text"]
    assert facts_text.index("历史对话") < facts_text.index("命中的标准答案")


def test_blank_history_turns_are_dropped(faq_index) -> None:
    """空内容的历史轮次直接丢掉，不占 prompt 位置。"""
    provider = _RecordingProvider()
    service = AssistantService(faq_index, provider=provider)
    service.chat(
        ChatRequest(
            question="陪诊费多少钱",
            history=[HistoryTurn(role="user", content="   ")],
        )
    )
    assert provider.facts is not None
    assert provider.facts["history"] == []


def test_no_history_section_when_history_is_empty(faq_index) -> None:
    """没有历史时**不出现**历史段（空段只会让模型多读一行废话）。"""
    provider = _RecordingProvider()
    service = AssistantService(faq_index, provider=provider)
    service.chat(ChatRequest(question="陪诊费多少钱"))
    assert provider.facts is not None
    assert "历史对话" not in provider.facts["facts_text"]


# ---------------------------------------------------------------------------
# 前端预设问题不许掉进 UNKNOWN（V3.7 联调回归）
# ---------------------------------------------------------------------------
#
# ⚠️ 这四个问题**逐字取自** frontend/src/views/family/assistant.vue 的 ``presets``：
#
#     const presets = ['陪诊费怎么算？', '下了单还能取消吗？', '我的订单到哪了？', '帮我找个合适的陪诊员']
#
# 背景（V3.7 联调实测）：页面**自己的**首个预设问题「陪诊费怎么算？」
# 曾经返回「我暂时没听懂」—— 费用条目的关键词是「多少钱 / 服务费 / 费用 / 收费 / 价格」，
# **一个都不含**「陪诊费怎么算」这句里的字，于是 FAQ 检索没命中，掉进 UNKNOWN。
#
# 也就是说：**应用推荐给用户的第一个问题，助手自己答不上来。**
# 单元测试此前没发现，是因为黄金集用的是「陪诊费多少钱」（含「多少钱」→ 命中）。
#
# 前端 presets 与本文件是**跨语言**的，所以这里用「逐字复制 + 注明出处」的方式锁住；
# 另一头由 Playwright（``e2e/specs/21-assistant.spec.js``）真点一遍四个预设，
# 断言回答不是澄清话术 —— 那一头才是端到端的保证。
# ---------------------------------------------------------------------------

FRONTEND_PRESET_QUESTIONS = [
    ("陪诊费怎么算？", "FAQ_PLATFORM"),
    ("下了单还能取消吗？", "FAQ_PLATFORM"),
    ("我的订单到哪了？", "ORDER_STATUS"),
    ("帮我找个合适的陪诊员", "RECOMMEND_COMPANION"),
]


@pytest.mark.parametrize(
    "question,expected_intent",
    FRONTEND_PRESET_QUESTIONS,
    ids=[q for q, _ in FRONTEND_PRESET_QUESTIONS],
)
def test_frontend_preset_questions_are_answerable(
    service: AssistantService, question: str, expected_intent: str
) -> None:
    """前端预设的每个问题都必须答得上来（不许掉进 UNKNOWN）。"""
    response = service.chat(ChatRequest(question=question))
    assert response.intent == expected_intent, (
        f"「{question}」应判为 {expected_intent}，实际 {response.intent}"
    )
    assert response.answer != guard.CLARIFY_TEMPLATE, (
        f"「{question}」不该回澄清话术 —— 那是「没听懂」"
    )


@pytest.mark.parametrize(
    "question",
    [
        "下周三去海南省人民医院心内科帮我约陪诊",
        "帮我约个陪诊",
        "我要预约陪诊",
    ],
)
def test_ordering_questions_are_not_taken_as_unheard(
    service: AssistantService, question: str
) -> None:
    """下单意图不该被当成「没听懂」（V3.7 联调回归）。

    背景：这类问题会同时产出 ``FILL_ORDER_DRAFT`` 卡片（就诊信息抽到了）。
    若它又掉进 UNKNOWN，界面上就会出现
    **「我暂时没听懂」** 与 **「已为你填好下单信息」** 并排出现 ——
    同一屏里自相矛盾：一边说没听懂，一边把订单都填好了。

    修法是让这类问题命中「怎么给老人下单？」那条 FAQ：
    意图变成 ``FAQ_PLATFORM``，答案讲下单流程并带依据引用，
    卡片在下方补充用户自己给的具体信息，两边说得通。
    """
    response = service.chat(ChatRequest(question=question))
    assert response.intent == "FAQ_PLATFORM", (
        f"「{question}」应判为 FAQ_PLATFORM，实际 {response.intent}"
    )
    assert response.answer != guard.CLARIFY_TEMPLATE
    assert response.sources, "下单类回答应带依据引用"
