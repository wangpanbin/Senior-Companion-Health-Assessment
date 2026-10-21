"""确定性意图分类器。

## 为什么不用 LLM 做分类

两个理由：

1. **无 LLM 也必须能分类** —— 降级路径与主路径同构是本版的硬要求
   （ADR-0020 决策 4）。如果分类依赖 LLM，那么「没配 key」时整条链路就断了，
   降级就变成了补丁而不是同一条路。
2. **在「谨慎使用」档里，可审计性比灵活性重要** —— 分类结果决定「答什么」，
   它必须是**规则**、可被单测逐条锁死，而不是模型的一次判断。

分类结果只决定**走哪条固定分支**，不决定事实内容。
"""

from __future__ import annotations

#: 订单状态类关键词。命中 → ``ORDER_STATUS``。
ORDER_STATUS_KEYWORDS: tuple[str, ...] = (
    "我的订单", "订单状态", "订单进度", "到哪了", "到哪儿了",
    "接单了吗", "接单没", "谁接了", "谁接单", "接单情况",
    "什么时候来", "什么时候到", "来了吗", "开始了吗",
    "完成了没", "完成了吗", "做完了吗", "结束了没",
    "催单", "催一下", "还没人接", "没人接单", "多久能接",
)

#: 推荐类关键词。命中 → ``RECOMMEND_COMPANION``。
#:
#: ⚠️ 刻意**不用裸词「陪诊员」**：那会把「陪诊员资质怎么审核」也路由到推荐分支
#:    （推荐分支在 FAQ 检索**之前**，而资质问题本来有一条现成的 FAQ）。
#:    改用**具体搭配**，既覆盖「这几位陪诊员为什么合适」这类问法，
#:    又不误伤资质 / 打卡 / 评价类问题。
RECOMMEND_KEYWORDS: tuple[str, ...] = (
    "推荐", "找个", "找谁", "安排", "派个", "派谁",
    "哪个陪诊员", "哪些陪诊员", "几位陪诊员", "陪诊员推荐",
    "陪诊员为什么", "为什么合适", "谁合适", "合适的陪诊员",
    "找个师傅", "谁能陪", "谁去陪", "匹配",
)


def match_keywords(text: str, keywords: tuple[str, ...]) -> str | None:
    """返回第一个命中的关键词，未命中返回 ``None``。"""
    if not text:
        return None
    for word in keywords:
        if word in text:
            return word
    return None


def classify_order_status(text: str) -> bool:
    """是否属于订单状态类问题。"""
    return match_keywords(text, ORDER_STATUS_KEYWORDS) is not None


def classify_recommend(text: str) -> bool:
    """是否属于推荐陪诊员类问题。"""
    return match_keywords(text, RECOMMEND_KEYWORDS) is not None


__all__ = [
    "ORDER_STATUS_KEYWORDS",
    "RECOMMEND_KEYWORDS",
    "classify_order_status",
    "classify_recommend",
    "match_keywords",
]
