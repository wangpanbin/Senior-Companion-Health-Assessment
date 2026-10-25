"""内部接口的线缆模型（Java 门面 ⇄ Python）。

## 为什么字段名用 camelCase

本项目后端是 Java + Jackson，线缆格式统一是 **camelCase**（`docs/api/README.md` §七）。
这些模型**只在内部服务之间流转**，不直接对外暴露给浏览器，
所以直接用 camelCase 命名，省掉一层 alias 映射 —— 也少一个出错的地方。

⚠️ 对外契约仍是 `/api/assistant/chat`（Java 门面），本模块不对外暴露。
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field

#: 助手的意图枚举。顺序即优先级（见 intents.py）。
Intent = Literal[
    "FAQ_PLATFORM",
    "RECOMMEND_COMPANION",
    "ORDER_STATUS",
    "REFUSE_MEDICAL",
    "UNKNOWN",
]

#: 结构化动作类型。
ActionType = Literal["FILL_ORDER_DRAFT", "NAVIGATE"]


class HistoryTurn(BaseModel):
    """一轮历史对话。

    长度上限与 Java 侧 ``AssistantHistoryTurn``（role ≤20 / content ≤1000）
    同口径 —— 门面虽已校验，内部端口只监听 127.0.0.1 也挡不住本机其它进程直调，
    不设上限等于把「撑大 payload 烧模型 token」的口子留在内网侧。
    """

    role: str = Field(default="user", max_length=20, description="user / assistant")
    content: str = Field(default="", max_length=1000, description="该轮内容")


class ElderBrief(BaseModel):
    """老人档案摘要（**脱敏姓名**，与后端 ElderVO 列表口径一致）。"""

    id: int
    name: str = ""


class OrderBrief(BaseModel):
    """订单上下文摘要。字段全部来自后端 OrderVO，不额外补充。"""

    id: int | None = None
    orderNo: str | None = None
    status: str | None = None
    statusLabel: str | None = None
    hospital: str | None = None
    department: str | None = None
    visitTime: str | None = None
    address: str | None = None
    companionName: str | None = None


class Candidate(BaseModel):
    """候选陪诊员。

    字段与后端 ``CompanionMatchVO`` **逐字对应** —— 三段式可解释结构
    （``reasons`` / ``mismatches`` / ``notes``）必须分开传递，
    合并它们正是 ADR-0016 明令禁止的事。
    """

    companionId: int | None = None
    companionName: str | None = None
    totalScore: int | None = None
    regionScore: int | None = None
    timeScore: int | None = None
    distanceScore: int | None = None
    skillScore: int | None = None
    ratingScore: int | None = None
    reasons: list[str] = Field(default_factory=list)
    mismatches: list[str] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)


class RecentOrder(BaseModel):
    """最近订单摘要（用于回答「我的订单到哪了」）。"""

    id: int | None = None
    orderNo: str | None = None
    status: str | None = None
    statusLabel: str | None = None
    visitTime: str | None = None
    hospital: str | None = None
    department: str | None = None


class ChatRequest(BaseModel):
    """Java 门面转发过来的自包含 payload。

    这里**没有任何令牌**：Python 不做鉴权，也不认识 Java 的地址
    （ADR-0020 决策 6）。
    """

    question: str = Field(min_length=1, max_length=500, description="用户问题（与 Java DTO 同口径）")
    history: list[HistoryTurn] = Field(
        default_factory=list, max_length=12, description="历史轮次（≤12 条，与 Java DTO 同口径）"
    )
    elders: list[ElderBrief] = Field(default_factory=list, description="当前家属绑定的老人（脱敏）")
    order: OrderBrief | None = Field(default=None, description="当前订单上下文")
    candidates: list[Candidate] = Field(default_factory=list, description="候选陪诊员（规则引擎已算好）")
    recentOrders: list[RecentOrder] = Field(default_factory=list, description="最近订单摘要")


class Action(BaseModel):
    """结构化动作。``payload`` 是动作特有的键值对。"""

    type: ActionType
    payload: dict[str, Any] = Field(default_factory=dict)


class ChatResponse(BaseModel):
    """返回给 Java 门面的结果（Java 会再包一层 ``Result``）。"""

    answer: str = Field(description="自然语言回答，已过输出护栏")
    intent: Intent = Field(description="命中的意图")
    actions: list[Action] = Field(default_factory=list, description="结构化动作")
    sources: list[str] = Field(default_factory=list, description="答案的依据引用")
    degraded: bool = Field(default=False, description="是否走了降级路径（未接入大模型）")
