"""FAQ 知识库：加载与检索。

## 为什么不用向量库

领域只有 25–30 条 FAQ，语义检索的收益远小于依赖成本（embedding 模型要下载、
CI 会变慢、评审机器装不上），而且**语义检索不可解释** —— 命中了哪条、为什么命中，
说不清楚。关键词 + 2-gram 重叠说得清楚，也能直接显示给用户看。

见 ADR-0020 §八「用向量库做 RAG」被否决的条目。

## 检索算法

1. 用户问题取 **2-gram 字符集合**；
2. 对每条 FAQ：
   - ``keyword_hit``：问题中**子串包含**任一 ``keywords`` 的个数（每个权重 3.0）——
     中文短问法里关键词几乎原样出现（「取消」「退单」），子串包含足够稳；
   - ``question_overlap``：2-gram 集合的 Jaccard 相似度（权重 2.0）；
3. 加权求和取 top-k；最高分低于阈值 → 未命中。
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

#: 命中一个 keyword 的权重。
KEYWORD_WEIGHT = 3.0
#: question 的 2-gram 重叠权重。
QUESTION_WEIGHT = 2.0
#: 低于此分视为未命中 → UNKNOWN。
MIN_SCORE = 2.0


class FaqError(RuntimeError):
    """FAQ 文件缺失或格式非法。"""


@dataclass(frozen=True)
class FaqEntry:
    """一条 FAQ。"""

    id: str
    intent: str
    question: str
    keywords: tuple[str, ...]
    answer: str
    source: str = ""
    anchor: str = ""

    @property
    def source_ref(self) -> str:
        """依据引用（给前端展示的 ``sources``）。

        形如 ``docs/api/03-order.md §1. 创建订单``。

        ⚠️ 锚点原文带 Markdown 标题符号（``## 1. 创建订单``），直接拼会变成
        ``docs/api/03-order.md ## 1. 创建订单`` —— 读着像把 Markdown 泄进了业务文案。
        所以这里去掉标题符号、补一个 ``§``。
        """
        if self.source and self.anchor:
            heading = self.anchor.lstrip("#").strip()
            return f"{self.source} §{heading}"
        return self.source


@dataclass
class FaqIndex:
    """FAQ 索引。"""

    entries: list[FaqEntry] = field(default_factory=list)

    def search(self, query: str, top_k: int = 3) -> list[tuple[FaqEntry, float]]:
        """检索 top-k。返回 ``(条目, 分数)`` 列表，按分数降序。"""
        if not query:
            return []
        query_grams = ngrams(query)
        scored: list[tuple[FaqEntry, float]] = []
        for entry in self.entries:
            score = score_entry(query, query_grams, entry)
            if score > 0:
                scored.append((entry, score))
        scored.sort(key=lambda item: (-item[1], item[0].id))
        return scored[:top_k]

    def best(self, query: str) -> tuple[FaqEntry, float] | None:
        """最高分条目。低于阈值返回 ``None``。"""
        hits = self.search(query, top_k=1)
        if not hits:
            return None
        entry, score = hits[0]
        return (entry, score) if score >= MIN_SCORE else None


def ngrams(text: str, n: int = 2) -> set[str]:
    """字符 n-gram 集合。

    中文没有空格分词，但「取消」「退单」这类词在短问法里几乎原样出现，
    2-gram 已经足够把它们切出来 —— 不需要引入分词库。
    """
    if not text:
        return set()
    compact = "".join(text.split())
    if len(compact) < n:
        return {compact}
    return {compact[i : i + n] for i in range(len(compact) - n + 1)}


def jaccard(left: set[str], right: set[str]) -> float:
    """Jaccard 相似度。两个空集合视为 0。"""
    if not left or not right:
        return 0.0
    intersection = len(left & right)
    union = len(left | right)
    return intersection / union if union else 0.0


def score_entry(query: str, query_grams: set[str], entry: FaqEntry) -> float:
    """给单条 FAQ 打分。"""
    keyword_hits = sum(1 for kw in entry.keywords if kw and kw in query)
    overlap = jaccard(query_grams, ngrams(entry.question))
    return keyword_hits * KEYWORD_WEIGHT + overlap * QUESTION_WEIGHT


def load_faq(path: Path) -> FaqIndex:
    """加载 FAQ 文件。

    :raises FaqError: 文件缺失、JSON 非法、或缺少必需字段。
    """
    if not path.is_file():
        raise FaqError(f"FAQ 文件不存在：{path}")
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise FaqError(f"FAQ 文件不是合法 JSON：{path}（{exc}）") from exc
    if not isinstance(raw, list):
        raise FaqError(f"FAQ 文件顶层必须是数组：{path}")

    entries: list[FaqEntry] = []
    for index, item in enumerate(raw):
        if not isinstance(item, dict):
            raise FaqError(f"FAQ 第 {index + 1} 条不是对象：{path}")
        missing = [key for key in ("id", "question", "answer") if not item.get(key)]
        if missing:
            raise FaqError(f"FAQ 第 {index + 1} 条缺字段 {missing}：{path}")
        entries.append(
            FaqEntry(
                id=str(item["id"]),
                intent=str(item.get("intent", "FAQ_PLATFORM")),
                question=str(item["question"]),
                keywords=tuple(str(k) for k in item.get("keywords", [])),
                answer=str(item["answer"]),
                source=str(item.get("source", "")),
                anchor=str(item.get("anchor", "")),
            )
        )
    return FaqIndex(entries=entries)


__all__ = [
    "FaqEntry",
    "FaqError",
    "FaqIndex",
    "KEYWORD_WEIGHT",
    "MIN_SCORE",
    "QUESTION_WEIGHT",
    "jaccard",
    "load_faq",
    "ngrams",
    "score_entry",
]
