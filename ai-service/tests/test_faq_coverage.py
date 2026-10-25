"""FAQ 关键词覆盖门禁（V3.7 收口 · 问法池）。

## 为什么要有这道门禁

FAQ 的检索是「**keyword 子串命中** × 3.0 + 2-gram Jaccard × 2.0」，阈值 `MIN_SCORE = 2.0`。
Jaccard 最大 1.0 → 最多 2.0 分，**恰好等于阈值** ——
所以能稳定命中的只有「问题里包含某条 keyword」这一条路。

也就是说 **keywords 就是命中率本身**。V3.7 联调实测已经抓到两次关键词缺口：
前端自己的首个预设问题「陪诊费怎么算？」答不上来（费用条关键词不含「陪诊费」），
下单条也不认「约陪诊」。靠人工推导补词会一直漏。

所以「补词」被做成了**可复现的动作**（`tools/gen_faq_questions.py`），
而「补得对不对」由**确定性检索**说了算 —— 不是模型说了算。

## 问法池是什么、不是什么

问法池由**大模型批量生成**（30 条 FAQ × 6 条 = 180 条），
是**高仿真的模拟问法**，**不是真实用户数据**。
后来新增 elder-health 5 条时**手写补了 25 条**（每条 5 条，见池文件末尾段）——
新增问法一律走「确定性检索验收」这条同一标准，来源不重要，命中与不抢占才重要。

⚠️ 因此 `docs/prd/PRD-V3.7-家属智能助手.md` 风险表第 5 条
「FAQ 覆盖面未经真实用户验证」**保持未关闭** ——
它只能靠上线后的未命中问题回流来关闭。这道门禁**不能**关闭它。

## 门禁口径

| 断言 | 阈值 | 为什么是这个阈值 |
|---|---|---|
| 召回：每条问法命中它声明的条目 | ≥ 95%（生成段 171/180） | 剩下 6 条是**近义条目对**（状态 01/02、催促 01/02、投诉 01/02、推荐 01/02）之间的**真歧义**，见 `KNOWN_AMBIGUOUS`；手写段要求 100% 命中 |
| 不抢占：目标条目得分**严格**高于其它所有条目 | 100% | 抢占 = 家属问 A 答 B，比答不上更糟 |
| 问法池里**没有医疗意图提问** | 0 条 | 医疗问法会被输入护栏拦成 `REFUSE_MEDICAL`，根本到不了检索，放进池子是纯噪声 |
| 每条目关键词数 | ≤ 14 | 实测扫描：压到 6 → 召回 86.7%；压到 14 → 96.7% 且与不限同值 |
| 易混对（硬编码） | 全部通过 | 锁住「刻意不加泛词」那条决策 |

**核心断言不依赖 LLM 与网络** —— 全部走确定性检索，
所以在「没配模型」的机器上也必须能跑绿。
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest

from app import guard
from app.knowledge import FaqIndex, load_faq

KNOWLEDGE_DIR = Path(__file__).resolve().parent.parent / "knowledge"
FAQ_PATH = KNOWLEDGE_DIR / "faq.json"
POOL_PATH = KNOWLEDGE_DIR / "faq-question-pool.json"

#: 召回率下限（180 条里至少 171 条）。
MIN_RECALL = 0.95

#: 每条目关键词数上限（与 tools/gen_faq_questions.py 保持同口径）。
MAX_KEYWORDS_PER_ENTRY = 14

#: 已知歧义：这些问法在**两条近义 FAQ** 之间都讲得通，
#: 检索给出的答案同样合理 —— 不要求它们命中生成器标的条目。
#:
#: 强行让它们命中「标准条目」需要往条目里塞两个关键词（靠分数压制兄弟条目），
#: 那是**按噪声调参**。所以这里记录在案，而不是硬凑召回。
KNOWN_AMBIGUOUS = {
    "订单状态是按什么顺序走的",                     # 状态 01（有哪些状态）vs 02（怎么一步步变）
    "一直催会不会有上限",                            # 催促 01（怎么催）vs 02（能催几次）
    "催太频繁了",                                 # 同上
    "投诉单子状态怎么流转的",                        # 投诉 01（怎么提交）vs 02（怎么跟进）
    "投诉怎么跟进",                              # 同上
    "我在订单详情里找不到能跟陪诊员说话的地方是不是还没人接单",  # 复合问法
}

#: 易混对 —— 硬编码锁住「刻意不加泛词」那条决策。
#:
#: ⚠️ 费用条**刻意没有**加泛词「怎么算」——
#: 加上它，「推荐陪诊员是怎么算出来的？」会被费用条抢走。
#: 这条是 V3.7 联调时踩过的坑，用例要钉死。
EASY_CONFUSION = [
    ("推荐陪诊员是怎么算出来的？", "faq-companion-recommend-01"),
    ("陪诊费怎么算？", "faq-order-fee-01"),
    ("怎么取消订单？", "faq-order-cancel-01"),
    ("怎么投诉？", "faq-complaint-submit-01"),
    ("平台能告诉我这个药怎么用吗？", "faq-medication-boundary-01"),
]


@pytest.fixture(scope="module")
def faq_index() -> FaqIndex:
    return load_faq(FAQ_PATH)


@pytest.fixture(scope="module")
def pool() -> list[dict]:
    if not POOL_PATH.is_file():
        pytest.fail("问法池不存在：{}（先跑 tools/gen_faq_questions.py --live）".format(POOL_PATH))
    return json.loads(POOL_PATH.read_text(encoding="utf-8"))


def test_pool_covers_every_faq_entry(pool: list[dict], faq_index: FaqIndex) -> None:
    """问法池覆盖全部 FAQ 条目，每条至少 5 条问法。

    ⚠️ 断言的是**覆盖不变量**，不是「正好 180 条」——
    永远到不了检索的问法（会被输入护栏判成医疗意图）会被剔除，
    条数因此可能少于 30×6。剔除清单见 ``tools/gen_faq_questions.py`` 的
    ``DROP_QUESTIONS``。
    """
    per_entry: dict[str, int] = {}
    for item in pool:
        per_entry[item["expectId"]] = per_entry.get(item["expectId"], 0) + 1
    assert sorted(per_entry) == sorted(entry.id for entry in faq_index.entries)
    thin = {k: v for k, v in per_entry.items() if v < 5}
    assert not thin, "这些条目的问法覆盖不足 5 条：{}".format(thin)
    assert len(pool) >= len(faq_index.entries) * 5


def test_pool_questions_are_unique(pool: list[dict]) -> None:
    """问法池里**不得有重复问法**（重复会让召回率分母虚高）。"""
    questions = [item["question"] for item in pool]
    assert len(questions) == len(set(questions)), "问法池里有重复问法"


def test_every_question_hits_its_entry(faq_index: FaqIndex, pool: list[dict]) -> None:
    """召回：每条问法都要命中它声明的条目（≥ 95%）。"""
    misses: list[str] = []
    for item in pool:
        found = faq_index.best(item["question"])
        if found is None or found[0].id != item["expectId"]:
            actual = found[0].id if found else None
            misses.append("「{}」期望 {} → 实际 {}".format(
                item["question"], item["expectId"], actual))
    recall = 1.0 - len(misses) / max(len(pool), 1)
    assert recall >= MIN_RECALL, "召回率 {:.1%} < {:.0%}，未命中：\n{}".format(
        recall, MIN_RECALL, "\n".join(misses))


def test_misses_are_only_the_known_ambiguous_ones(faq_index: FaqIndex, pool: list[dict]) -> None:
    """未命中的必须是**已知的近义条目歧义**，不能有新的失败。"""
    misses = {
        item["question"]
        for item in pool
        if (lambda f: f is None or f[0].id != item["expectId"])(faq_index.best(item["question"]))
    }
    unexpected = misses - KNOWN_AMBIGUOUS
    assert not unexpected, "出现了未登记的未命中问法：\n{}".format(
        "\n".join(sorted(unexpected)))
    assert misses <= KNOWN_AMBIGUOUS, "登记的歧义问法变少了 —— 需更新 KNOWN_AMBIGUOUS"


def test_no_entry_steals_another_entries_question(faq_index: FaqIndex, pool: list[dict]) -> None:
    """不抢占：目标条目的得分必须**严格**高于其它所有条目。"""
    offenders: list[str] = []
    for item in pool:
        found = faq_index.best(item["question"])
        if found is None or found[0].id != item["expectId"]:
            continue
        target_score = found[1]
        for other, score in faq_index.search(item["question"], top_k=5):
            if other.id != item["expectId"] and score >= target_score:
                offenders.append("「{}」→ {}（{} 分）与 {}（{} 分）同分或更高".format(
                    item["question"], item["expectId"], round(target_score, 2),
                    other.id, round(score, 2)))
    assert not offenders, "以下问法存在抢占：\n{}".format("\n".join(offenders))


def test_pool_has_no_medical_intent_question(pool: list[dict]) -> None:
    """问法池里**不得**有医疗意图提问。

    医疗问法会被输入护栏拦成 ``REFUSE_MEDICAL``，根本到不了 FAQ 检索 ——
    放进池子里只会虚增「问法数量」，而命中率分母是虚的。
    """
    offenders = [
        item["question"] for item in pool
        if guard.find_medical_intent(item["question"]) is not None
    ]
    assert not offenders, "问法池里混进了医疗意图提问：\n{}".format("\n".join(offenders))


def test_pool_questions_contain_no_medical_advice_word(pool: list[dict]) -> None:
    """问法本身**不得**含医疗建议类禁用词（它们会被输出护栏整段替换）。"""
    offenders = [
        item["question"] for item in pool
        if any(word in item["question"] for word in guard.medical_advice_words())
    ]
    assert not offenders, "问法池里混进了医疗建议禁用词：\n{}".format("\n".join(offenders))


def test_keyword_count_stays_bounded(faq_index: FaqIndex) -> None:
    """每条目关键词数 ≤ 上限。

    关键词是**子串匹配** —— 多一个词就多一次在池子外问法上误命中的机会。
    """
    offenders = [
        "{} 有 {} 个".format(entry.id, len(entry.keywords))
        for entry in faq_index.entries
        if len(entry.keywords) > MAX_KEYWORDS_PER_ENTRY
    ]
    assert not offenders, "关键词数超限：\n{}".format("\n".join(offenders))


@pytest.mark.parametrize("question,expect_id", EASY_CONFUSION)
def test_easy_confusion_pairs(faq_index: FaqIndex, question: str, expect_id: str) -> None:
    """易混对：容易被别的条目抢走的问法，必须回到正确条目。"""
    found = faq_index.best(question)
    assert found is not None, "「{}」没命中任何条目".format(question)
    assert found[0].id == expect_id, "「{}」应命中 {}，实际 {}（{} 分）".format(
        question, expect_id, found[0].id, round(found[1], 2))
