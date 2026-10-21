"""知识库与检索测试。

## 最重要的一条

**FAQ 答案里不得含任何禁用词。** 因为输出护栏命中禁用词会**整段拒答** ——
一条答案踩了禁用词，等于这条 FAQ 在降级模式下会把家属的话也一起拒掉，
家属什么都拿不到。这条断言是「FAQ 内容 ↔ 护栏」之间那根唯一的保险丝。
"""

from __future__ import annotations

import pytest

from app import guard, knowledge


def test_faq_has_enough_entries(faq_index: knowledge.FaqIndex) -> None:
    """FAQ 条目数 ≥ 25（覆盖真实提问分布，而不是只够演示）。"""
    assert len(faq_index.entries) >= 25


def test_faq_ids_are_unique(faq_index: knowledge.FaqIndex) -> None:
    ids = [entry.id for entry in faq_index.entries]
    assert len(ids) == len(set(ids))


def test_every_entry_has_required_fields(faq_index: knowledge.FaqIndex) -> None:
    for entry in faq_index.entries:
        assert entry.id, "id 不能为空"
        assert entry.question, f"{entry.id} 缺少 question"
        assert entry.answer, f"{entry.id} 缺少 answer"
        assert entry.keywords, f"{entry.id} 缺少 keywords"
        assert entry.source, f"{entry.id} 缺少 source"
        assert entry.anchor, f"{entry.id} 缺少 anchor"


def test_no_faq_answer_contains_forbidden_word(
    faq_index: knowledge.FaqIndex, medical_words: tuple[str, ...]
) -> None:
    """**每条 FAQ 的答案都不得含禁用词。**

    命中即整段拒答 —— 这样的 FAQ 在降级模式下等于没有。
    """
    offenders: list[str] = []
    for entry in faq_index.entries:
        for word in medical_words:
            if word in entry.answer:
                offenders.append(f"{entry.id}: {word}")
    assert not offenders, "以下 FAQ 答案含禁用词（会被输出护栏整段拒答）：" + "；".join(offenders)


def test_faq_source_files_exist(faq_index: knowledge.FaqIndex) -> None:
    """每条 FAQ 的 source 指向的文件必须真实存在（由 tools/check_faq_anchors.py 做逐条校验）。"""
    from pathlib import Path

    root = Path(__file__).resolve().parents[2]
    for entry in faq_index.entries:
        assert (root / entry.source).is_file(), f"{entry.id} 的 source 不存在：{entry.source}"


@pytest.mark.parametrize(
    ("query", "expect_in_question"),
    [
        ("下了单还能取消吗", "取消"),
        ("陪诊费多少钱", "费"),
        ("评价在哪里提交", "评价"),
        ("投诉怎么提", "投诉"),
        ("怎么催单", "催"),
    ],
)
def test_retrieval_top1_is_sensible(
    faq_index: knowledge.FaqIndex, query: str, expect_in_question: str
) -> None:
    """检索 top-1 命中的条目，其 question 应包含该问题的核心词。

    这里断言的是「命中的是相关条目」而不是「命中了某个固定 id」——
    因为 FAQ 可以增删，绑定 id 会让内容迭代必然改测试。
    """
    hit = faq_index.best(query)
    assert hit is not None, f"查询「{query}」没有命中任何 FAQ"
    entry, score = hit
    assert expect_in_question in entry.question, (
        f"查询「{query}」命中了不相关的条目：{entry.question}"
    )
    assert score >= knowledge.MIN_SCORE


def test_retrieval_returns_none_for_unrelated_query(faq_index: knowledge.FaqIndex) -> None:
    """完全无关的提问 → 不命中（→ UNKNOWN → 澄清话术），而不是硬凑一条。"""
    assert faq_index.best("今天天气怎么样") is None


def test_ngrams_and_jaccard_behave() -> None:
    grams = knowledge.ngrams("取消订单")
    assert "取消" in grams
    assert "订单" in grams
    assert knowledge.jaccard(set(), set()) == 0.0
    assert knowledge.jaccard({"a"}, {"a"}) == 1.0


def test_source_ref_is_readable(faq_index: knowledge.FaqIndex) -> None:
    entry = faq_index.entries[0]
    assert entry.source_ref.startswith(entry.source)


def test_source_ref_uses_section_symbol_not_markdown_headings(
    faq_index: knowledge.FaqIndex,
) -> None:
    """依据引用里**不该**漏出 Markdown 标题符号。

    锚点原文是 ``## 1. 创建订单``，直接拼会变成
    ``docs/api/03-order.md ## 1. 创建订单`` —— 像把 Markdown 泄进了业务文案。
    """
    for entry in faq_index.entries:
        assert "##" not in entry.source_ref, f"{entry.id} 的依据引用漏出了标题符号"
        assert "§" in entry.source_ref, f"{entry.id} 的依据引用应有章节号"


def test_guard_refusal_template_is_not_used_as_faq_answer(
    faq_index: knowledge.FaqIndex,
) -> None:
    """FAQ 答案不应就是拒答模板 —— 那说明有条 FAQ 是「医疗类」主题。

    医疗类问题由输入护栏直接拒答，不需要 FAQ 条目。
    """
    for entry in faq_index.entries:
        assert entry.answer.strip() != guard.REFUSAL_TEMPLATE.strip(), (
            f"{entry.id} 的答案就是拒答模板 —— 医疗类问题交给输入护栏，不要写成 FAQ"
        )
