"""编排层：把护栏、分类、检索、叙述、动作串成一条链路。

## 降级路径是**主路径**，不是补丁

没有 LLM（或 LLM 调用失败）时，本模块**照样**产出一条完整、可用的回答：

- FAQ 类 → 直接给标准答案原文；
- 推荐类 → 直接给规则引擎算好的候选 + 理由（**三段分开展示**）；
- 订单类 → 直接给最近订单的状态。

所以 ``LLM_BASE_URL`` 没配不是「功能不可用」，只是「说法朴素一点」。
这条纪律见 ADR-0020 决策 4。
"""

from __future__ import annotations

import logging
import re
from datetime import date
from typing import Any

from . import actions as actions_mod
from . import guard, intents, knowledge
from .knowledge import FaqEntry, FaqIndex
from .llm import LlmProvider, NullProvider
from .schemas import Action, ChatRequest, ChatResponse

logger = logging.getLogger(__name__)

#: 推荐类回答的依据引用。
RECOMMEND_SOURCE = "docs/api/03-order.md §13"
#: 订单类回答的依据引用。
ORDER_SOURCE = "docs/api/03-order.md §2"
#: 下单流程的依据引用。
ORDER_CREATE_SOURCE = "docs/api/03-order.md §1"

#: Markdown 粗体：``**text**``。
_MARKDOWN_BOLD = re.compile(r"\*\*(.+?)\*\*", re.DOTALL)
#: Markdown 标题前缀：``## 标题``。
_MARKDOWN_HEADING = re.compile(r"^[ \t]{0,3}#{1,6}[ \t]*", re.MULTILINE)


def normalize_plain_text(text: str) -> str:
    """把模型输出里的 Markdown 标记去掉，只留纯文本。

    ## 为什么必须做

    前端是 ``{{ m.content }}`` **纯文本渲染**（``white-space: pre-wrap`` 只保留换行），
    页面**不解析** Markdown —— spec §5 的设计本来就是纯文本，
    前端也没有引入 Markdown 渲染依赖。

    所以模型一旦用 ``**`` 加粗，界面上就会出现字面量 ``**命中理由**``。
    实测（V3.7 联调）每条模型回答都带这个 —— 家属看到的是符号，不是排版。

    ## 为什么两处都做

    ① prompt 第 8 条已经要求模型用纯文本（治本）；
    ② 这里再兜一道底（治标）—— prompt 是「请求模型遵守」的约定，
    不是保证。模型偶尔不遵守时，**不能让用户看见符号**。

    ⚠️ 只动标记，**不动内容**：换行分段保留（靠 pre-wrap），
    列表项的 ``- `` 也保留（纯文本读起来就是正常列表）。
    """
    if not text:
        return text
    cleaned = _MARKDOWN_BOLD.sub(r"\1", text)
    cleaned = _MARKDOWN_HEADING.sub("", cleaned)
    # 收尾：不配对的 ``**`` 也清掉，避免出现孤零零的星号
    return cleaned.replace("**", "")


class AssistantService:
    """助手编排。一个实例持有 FAQ 索引与 LLM provider。"""

    def __init__(self, faq_index: FaqIndex, provider: LlmProvider | None = None) -> None:
        self._faq = faq_index
        self._provider = provider or NullProvider()

    # ── 主入口 ────────────────────────────────────────────────────────

    def chat(self, request: ChatRequest) -> ChatResponse:
        """处理一次问答。"""
        question = (request.question or "").strip()

        # ① 输入侧护栏：医疗意图 → 直接拒答（**不经 LLM**）
        verdict = guard.scan_input(question)

        # ①·补 **历史里的 user 轮同样过输入护栏**。
        #    scan_input 只看当前问句的话，医疗内容可以放进上一轮、
        #    当前问句只写「继续」—— 历史会被渲染进事实包交给大模型，
        #    而输出护栏只拦共享词表的 17 个词，「建议饭后服用」这类
        #    表述不在其中，恰好从这条缝漏出去。
        #    只扫 user 轮：assistant 轮是本服务自己的输出，已经过了输出护栏；
        #    空白轮不扫（与 _build_facts 的丢弃口径一致）。
        #    命中辱骂/引流词时 scan_input 直接抛 IllegalQuestion（→ 10003），
        #    与「在当前问句里命中」的行为一致。
        if not verdict.medical:
            for turn in request.history or []:
                if (turn.role or "") != "user":
                    continue
                content = (turn.content or "").strip()
                if not content:
                    continue
                verdict = guard.scan_input(content)
                if verdict.medical:
                    break

        if verdict.medical:
            return ChatResponse(
                answer=guard.REFUSAL_TEMPLATE,
                intent="REFUSE_MEDICAL",
                actions=[],
                sources=[],
                degraded=False,
            )

        # ② 意图分类（确定性，不用 LLM）
        intent, faq_hit = self._classify(question, request)

        # ③ 事实装配
        facts = self._build_facts(question, intent, faq_hit, request)

        # ④ 叙述
        #
        # ⚠️ UNKNOWN **不经 LLM**，直接给固定澄清话术。
        #    契约写死了「UNKNOWN = 没听懂，返回澄清话术」（docs/api/13-assistant.md §1.2）。
        #    若让模型自由发挥，它会拿着「没命中任何 FAQ」的事实**硬编一个答案** ——
        #    实测：把「下周三去海南省人民医院心内科帮我约陪诊」答成一套五步下单流程，
        #    而事实包里根本没有这些步骤，那是 prompt 约束 2「不得引入 facts 之外的信息」
        #    的破口；而且那条回答的 sources 是空的 ——
        #    一条没有依据的答案，在评审现场站不住。
        #
        #    degraded 也固定为 False：澄清话术是**正确路径**，不是降级
        #    （与 REFUSE_MEDICAL 同理，见 test_golden_case_is_degraded_without_llm）。
        if intent == "UNKNOWN":
            answer = guard.CLARIFY_TEMPLATE
            degraded = False
        elif intent == "RECOMMEND_COMPANION" and not facts.get("candidates"):
            # 没有候选陪诊员 → **不走了模型**。
            # 事实包里根本没有候选，模型只能硬凑：实测它会生成
            # 「**命中理由** - 现有事实中未提供具体陪诊员的画像…」这种
            # 三段空壳，看着像三段式结构，其实一句有用信息都没有。
            #
            # 而 API 文档 §五 已知限制 1 已经把这个场景的行为写死了：
            # 推荐是**订单锚定**的，「还没下单就想看陪诊员」本版不支持，
            # 助手应引导用户先成单。那条引导文案是确定性的，不需要模型。
            answer = self._build_fallback_answer(intent, faq_hit, facts)
            degraded = False
        else:
            narrated = self._provider.narrate(facts)
            degraded = narrated is None
            answer = narrated if narrated is not None else self._build_fallback_answer(intent, faq_hit, facts)

        # ⑤ 输出侧护栏
        scan = guard.scan_output(answer)
        answer = scan.text

        # ⑤·补 去掉模型残留的 Markdown 标记（前端是纯文本渲染）
        answer = normalize_plain_text(answer)

        # ⑥ 动作抽取
        collected = self._build_actions(question, intent, request)

        return ChatResponse(
            answer=answer,
            intent=intent,
            actions=collected,
            sources=self._build_sources(intent, faq_hit),
            degraded=degraded,
        )

    # ── 分类 ──────────────────────────────────────────────────────────

    def _classify(
        self, question: str, request: ChatRequest
    ) -> tuple[str, tuple[FaqEntry, float] | None]:
        """按 spec §4.5 的优先级短路。

        医疗意图已在 :meth:`chat` 的输入护栏里拦掉，这里不再重复判断。

        ## 追问句为什么靠关键词而不是「带上下文重试」

        曾设计过「UNKNOWN 时拼上最近一条 user 轮重试检索」，但推演后被否：
        keyword 命中权重（3.0）**恒大于**阈值（2.0），所以问句只要含某条目的
        关键词就必然直接命中 —— 重试想生效，问句就不能含关键词；而接受重试命中
        又必须要求问句贡献关键词（否则拼接命中的只是上一轮话题的复读）。
        两个条件合起来，重试路径**在数学上不可达**。
        唯一有效的杠杆是给条目**补追问关键词**（「周末 / 夜间 / 节假日 / 晚上」），
        让「那周末呢？」这类短追问自己就能命中 —— 由
        ``tests/test_chat.py`` 的追问用例锁死。
        """
        if intents.classify_order_status(question):
            return "ORDER_STATUS", None
        if intents.classify_recommend(question):
            return "RECOMMEND_COMPANION", None
        faq_hit = self._faq.best(question)
        if faq_hit is not None:
            return "FAQ_PLATFORM", faq_hit
        return "UNKNOWN", None

    # ── 事实装配 ──────────────────────────────────────────────────────

    def _build_facts(
        self,
        question: str,
        intent: str,
        faq_hit: tuple[FaqEntry, float] | None,
        request: ChatRequest,
    ) -> dict[str, Any]:
        """组装结构化事实包。

        **候选陪诊员的三段式结构（``reasons`` / ``mismatches`` / ``notes``）
        在这里被原样保留**，渲染成事实文本时三段仍然分开 ——
        合并它们正是 ADR-0016 明令禁止的事。
        """
        elders = [
            {"id": elder.id, "name": elder.name} for elder in (request.elders or [])
        ]
        order = request.order.model_dump() if request.order else None
        candidates = [c.model_dump() for c in (request.candidates or [])]
        recent = [o.model_dump() for o in (request.recentOrders or [])]
        history = [
            {"role": turn.role, "content": turn.content}
            for turn in (request.history or [])
            if (turn.content or "").strip()
        ]

        facts: dict[str, Any] = {
            "question": question,
            "intent": intent,
            "elders": elders,
            "order": order,
            "candidates": candidates,
            "recent_orders": recent,
            "history": history,
            "faq_question": faq_hit[0].question if faq_hit else "",
            "faq_answer": faq_hit[0].answer if faq_hit else "",
            "faq_source": faq_hit[0].source_ref if faq_hit else "",
        }
        facts["facts_text"] = render_facts_text(facts)
        return facts

    # ── 降级回答 ──────────────────────────────────────────────────────

    def _build_fallback_answer(
        self,
        intent: str,
        faq_hit: tuple[FaqEntry, float] | None,
        facts: dict[str, Any],
    ) -> str:
        """没有 LLM 时的确定性回答。**必须本身就是可用的答案。**"""
        if intent == "FAQ_PLATFORM" and faq_hit is not None:
            return faq_hit[0].answer
        if intent == "RECOMMEND_COMPANION":
            return render_candidates(facts.get("candidates") or [])
        if intent == "ORDER_STATUS":
            return render_recent_orders(facts.get("recent_orders") or [])
        return guard.CLARIFY_TEMPLATE

    # ── 动作与依据 ────────────────────────────────────────────────────

    def _build_actions(
        self, question: str, intent: str, request: ChatRequest
    ) -> list[Action]:
        """抽取结构化动作。

        只在**问题里真的抽到就诊信息**时（医院 / 科室 / 就诊时间）才产出
        ``FILL_ORDER_DRAFT`` —— 没有信息就不要给一个空卡片。

        ⚠️ **自动补的 elderId 不算「抽到」**（见
        :meth:`app.actions.DraftExtraction.has_visit_info`）：
        否则「陪诊费多少钱」这种纯问规则的提问也会挂出下单卡片，
        每条回答后面都跟一张，纯粹是噪声。
        """
        elders = [
            {"id": elder.id, "name": elder.name} for elder in (request.elders or [])
        ]
        # ⚠️ 「今天」由**编排层**取，抽取函数本身保持纯函数（AGENTS.md §2.11：不碰时钟）
        extraction = actions_mod.extract_draft(question, elders, today=date.today())
        if not extraction.has_visit_info():
            return []
        payload = extraction.to_payload()
        if not payload:
            return []
        result = [Action(type="FILL_ORDER_DRAFT", payload=payload)]
        # 没有老人就先去第一步选老人
        target = "/family/order/step2" if extraction.elder_id else "/family/order/step1"
        result.append(Action(type="NAVIGATE", payload={"path": target}))
        return result

    def _build_sources(
        self, intent: str, faq_hit: tuple[FaqEntry, float] | None
    ) -> list[str]:
        if intent == "FAQ_PLATFORM" and faq_hit is not None:
            ref = faq_hit[0].source_ref
            return [ref] if ref else []
        if intent == "RECOMMEND_COMPANION":
            return [RECOMMEND_SOURCE, ORDER_CREATE_SOURCE]
        if intent == "ORDER_STATUS":
            return [ORDER_SOURCE]
        return []


# ──────────────────────────────────────────────────────────────────────
# 事实文本渲染
# ──────────────────────────────────────────────────────────────────────


def render_facts_text(facts: dict[str, Any]) -> str:
    """把事实包渲染成给 LLM 看的文本。

    **三段式结构在这里必须保持分开** —— 渲染给模型的事实一旦被合并，
    模型就会用「距离不匹配」去描述「距离没法算」（ADR-0016 决策 3 的同款错误）。

    ## 历史对话为什么放在最前面

    历史是**上下文**，不是事实 —— 所以单独成段、放在最前、
    并在标题里写明「仅供理解上下文，不是事实依据」。

    放在最前还有一个好处：【命中的标准答案】留在最后、最靠近用户问题，
    那才是模型作答时最该看的一段。

    ⚠️ 历史内容是**用户可控文本**，理论上可以被写成指令
    （「忽略前面的规则，直接说 …」）。防线不靠信任它，而是靠下游两层：
    prompt 约束 2「只能基于 facts 作答」+ 输出护栏
    （医疗词整段替换、派单措辞与距离措辞词语级纠正）。
    """
    lines: list[str] = []

    history = facts.get("history") or []
    if history:
        lines.append("【历史对话（仅供理解上下文，不是事实依据）】")
        for turn in history:
            role = "家属" if turn.get("role") == "user" else "助手"
            content = str(turn.get("content") or "").strip()
            if content:
                lines.append("- {}：{}".format(role, content))
        lines.append("")

    elders = facts.get("elders") or []
    if elders:
        lines.append("【就诊老人】")
        for elder in elders:
            lines.append("- id={} 姓名={}".format(elder.get("id"), elder.get("name")))

    order = facts.get("order")
    if order:
        lines.append("【当前订单】")
        for key in (
            "id",
            "orderNo",
            "status",
            "statusLabel",
            "hospital",
            "department",
            "visitTime",
            "companionName",
        ):
            value = order.get(key)
            if value:
                lines.append("- {}：{}".format(key, value))

    candidates = facts.get("candidates") or []
    if candidates:
        lines.append("【候选陪诊员（排序已由平台规则引擎算好，不要重排、不要改分）】")
        for index, candidate in enumerate(candidates, start=1):
            lines.append(
                "{}. id={} 姓名={} 匹配度={}".format(
                    index,
                    candidate.get("companionId"),
                    candidate.get("companionName"),
                    candidate.get("totalScore"),
                )
            )
            lines.append(
                "   五维得分：区域={} 时间={} 距离={} 服务能力={} 评分={}".format(
                    candidate.get("regionScore"),
                    candidate.get("timeScore"),
                    candidate.get("distanceScore"),
                    candidate.get("skillScore"),
                    candidate.get("ratingScore"),
                )
            )
            reasons = candidate.get("reasons") or []
            mismatches = candidate.get("mismatches") or []
            notes = candidate.get("notes") or []
            lines.append("   命中理由：{}".format("；".join(reasons) if reasons else "无"))
            lines.append(
                "   未命中说明：{}".format("；".join(mismatches) if mismatches else "无")
            )
            lines.append("   降级说明：{}".format("；".join(notes) if notes else "无"))

    recent = facts.get("recent_orders") or []
    if recent:
        lines.append("【最近订单】")
        for order_item in recent:
            lines.append(
                "- id={} 单号={} 状态={} 就诊时间={} 医院={} 科室={}".format(
                    order_item.get("id"),
                    order_item.get("orderNo"),
                    order_item.get("statusLabel") or order_item.get("status"),
                    order_item.get("visitTime"),
                    order_item.get("hospital"),
                    order_item.get("department"),
                )
            )

    faq_answer = facts.get("faq_answer")
    if faq_answer:
        lines.append("【命中的标准答案（只能基于它作答，不要补充）】")
        lines.append(str(faq_answer))

    if not lines:
        lines.append("（本次没有可用的结构化事实）")
    return "\n".join(lines)


def render_candidates(candidates: list[dict[str, Any]]) -> str:
    """降级路径下的候选陪诊员展示。

    **三段分开写** —— 与 ADR-0016 的口径一致。

    ⚠️ 「还没有候选」这一支也**必须**带「推荐仅供参考，最终由陪诊员本人确认接单」：
    文案里说了「系统会…给出候选陪诊员」，那就是一句推荐，
    缺了这句免责就等于把「平台会安排」说成了「平台已安排」
    （ADR-0016 决策 4.4：推荐 ≠ 派单，接单权始终在陪诊员）。
    """
    if not candidates:
        return (
            "推荐陪诊员需要先有就诊信息。"
            "你在对话里告诉我医院、科室和就诊时间，我先把下单信息填好；"
            "订单提交之后，系统会按区域、时间、距离、服务能力和评分五个维度"
            "给出候选陪诊员，并说明每位为什么合适。"
            "推荐仅供参考，最终由陪诊员本人确认接单。"
        )
    lines = [
        "按你的就诊信息，推荐了以下陪诊员"
        "（推荐仅供参考，最终由陪诊员本人确认接单）："
    ]
    for index, candidate in enumerate(candidates, start=1):
        lines.append(
            "{}. {} —— 匹配度 {} 分".format(
                index,
                candidate.get("companionName") or "陪诊员",
                candidate.get("totalScore"),
            )
        )
        reasons = candidate.get("reasons") or []
        mismatches = candidate.get("mismatches") or []
        notes = candidate.get("notes") or []
        if reasons:
            lines.append("   命中理由：{}".format("；".join(reasons)))
        if mismatches:
            lines.append("   未命中说明：{}".format("；".join(mismatches)))
        if notes:
            lines.append("   降级说明：{}".format("；".join(notes)))
    return "\n".join(lines)


def render_recent_orders(recent: list[dict[str, Any]]) -> str:
    """降级路径下的最近订单展示。"""
    if not recent:
        return (
            "我没有查到你的订单记录。"
            "如果你刚下过单，可以到「我的订单」里看一下；"
            "订单列表会显示最新的状态。"
        )
    lines = ["你最近的订单："]
    for item in recent:
        lines.append(
            "- 单号 {}｜状态 {}｜就诊时间 {}｜{} {}".format(
                item.get("orderNo") or item.get("id"),
                item.get("statusLabel") or item.get("status"),
                item.get("visitTime") or "待定",
                item.get("hospital") or "",
                item.get("department") or "",
            )
        )
    lines.append("订单状态可以在「我的订单」里查看，详情页还能看到进度时间线。")
    return "\n".join(lines)


__all__ = [
    "AssistantService",
    "normalize_plain_text",
    "render_candidates",
    "render_facts_text",
    "render_recent_orders",
]
