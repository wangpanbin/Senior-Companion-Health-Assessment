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

        # ④ 叙述：LLM 优先，失败/未配置 → 降级为确定性回答
        narrated = self._provider.narrate(facts)
        degraded = narrated is None
        answer = narrated if narrated is not None else self._build_fallback_answer(intent, faq_hit, facts)

        # ⑤ 输出侧护栏
        scan = guard.scan_output(answer)
        answer = scan.text

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

        facts: dict[str, Any] = {
            "question": question,
            "intent": intent,
            "elders": elders,
            "order": order,
            "candidates": candidates,
            "recent_orders": recent,
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

        只在**确实抽到了就诊信息**时才产出 ``FILL_ORDER_DRAFT`` ——
        没有信息就不要给一个空卡片。
        """
        elders = [
            {"id": elder.id, "name": elder.name} for elder in (request.elders or [])
        ]
        extraction = actions_mod.extract_draft(question, elders)
        if extraction.is_empty():
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
    """
    lines: list[str] = []

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
            "address",
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
    """
    if not candidates:
        return (
            "推荐陪诊员需要先有就诊信息。"
            "你在对话里告诉我医院、科室和就诊时间，我先把下单信息填好；"
            "订单提交之后，系统会按区域、时间、距离、服务能力和评分五个维度"
            "给出候选陪诊员，并说明每位为什么合适。"
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


__all__ = ["AssistantService", "render_candidates", "render_facts_text", "render_recent_orders"]
