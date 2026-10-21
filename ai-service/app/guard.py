"""三层护栏（V3.7 · ADR-0020 决策 2）。

合规红线「不做诊断、不开药方」在代码层的落点。三层：

| 层 | 函数 | 作用 |
|---|---|---|
| 1 输入 | :func:`scan_input` | 医疗意图 → 直接拒答，**不经 LLM**；辱骂 / 引流 → 抛 ``IllegalQuestion`` |
| 2 Prompt | ``app/prompts.py`` | system prompt 写死约束（只基于 facts、不得美化 notes 等） |
| 3 输出 | :func:`scan_output` | 命中共享禁用词表 → **整段替换**为拒答模板；命中派单措辞 → 词语级纠正 |

## 两种拦截为什么处理方式不同

- **医疗内容**：命中即**整段拒答**。沿用 ``ComplianceCheckUtil`` 的既有决策 ——
  「命中即拒，不打码后放行」（打码后读者仍能猜出原意）。
  部分内容被拒掉之后，剩下的医学判断依然可能是错的，所以不能保留。
- **派单措辞**（「已指派」「已分配」「自动派单」）：这是**产品边界**问题而不是合规红线，
  纠正后语义依然正确（推荐 ≠ 派单，但推荐本身是允许说的）。所以做**词语级纠正**，
  保留其余内容，再追加一句免责 —— 整段丢掉会让家属白问一次。

## 一个必须自己检查的坑

**本模块的所有固定文案（拒答模板、免责句）本身不能含有禁用词** ——
否则拒答模板会被自己的输出护栏拦下，变成「拒答模板被拒答」的死循环。

所以拒答模板来自**共享资源文件**（与 Java 侧同一份），
``tests/test_guard.py`` 有一条断言专门守这个。

## 词表在 import 时加载

模块级常量在 import 时读共享文件 —— 读不到 / 读空 / 读错就**直接抛错**，
进程起不来。这正是我们要的：护栏失效不能等到「医疗建议被放行」之后才发现。
"""

from __future__ import annotations

from dataclasses import dataclass

from .paths import (
    ComplianceWordsError,
    load_compliance_words,
    load_refusal_template,
    load_sensitive_words,
)

# ──────────────────────────────────────────────────────────────────────
# 共享合规资产（import 时加载 —— 加载失败 = 进程起不来）
# ──────────────────────────────────────────────────────────────────────

#: 医疗建议禁用词（与 Java 侧 ``ComplianceCheckUtil`` 读同一个文件）。
_MEDICAL_ADVICE_WORDS: tuple[str, ...] = tuple(load_compliance_words())

#: 辱骂 / 违规引流词（与 Java 侧 ``SensitiveWordUtil`` 读同一个文件）。
_SENSITIVE_WORDS: tuple[str, ...] = tuple(load_sensitive_words())

#: 固定拒答模板（与 Java 侧 ``ComplianceCheckUtil#refusalTemplate()`` 读同一个文件）。
#:
#: ⚠️ **本串本身不含任何禁用词**（诊断 / 疑似 / 病情判断 / 用药方案 …）。
#: 若哪天有人把「诊断」写进来，输出护栏会把拒答模板自己拦下，
#: 家属看到的就是一段莫名其妙的提示 —— 极其难排查。
REFUSAL_TEMPLATE: str = load_refusal_template()

# ──────────────────────────────────────────────────────────────────────
# 固定文案
# ──────────────────────────────────────────────────────────────────────

#: 推荐类回答的免责句。推荐 ≠ 派单，接单权在陪诊员（ADR-0016 决策 4.4）。
RECOMMEND_DISCLAIMER = "以上推荐仅供参考，最终由陪诊员本人确认接单。"

#: 意图不明时的澄清话术。给出可问的方向，而不是一句「我不知道」。
CLARIFY_TEMPLATE = (
    "我暂时没听懂这个问题。你可以直接问我这些："
    "下单流程、陪诊费用、取消与改约、陪诊员怎么推荐、"
    "评价与投诉怎么提交、陪诊员打卡与签到。"
)

#: 助手不处理的问题类型的说明，与 REFUSAL_TEMPLATE 配套使用。
MEDICAL_INTENT_HINT = "涉及病情与用药的问题，平台不能给医疗建议，请咨询医生。"

# ──────────────────────────────────────────────────────────────────────
# 词表
# ──────────────────────────────────────────────────────────────────────

#: 医疗意图关键词（**输入侧粗判**，与输出侧的共享禁用词表是两件事）。
#:
#: 判定的目的**只是拒绝**，不是分类 —— 所以可以粗。
#: 刻意**不含**「药」「医院」「治疗」「检查」这类宽泛词：
#: 「已协助取药」是完全合法的过程记录（与 ``ComplianceCheckUtil`` 同一纪律）。
#:
#: ⚠️ 用**裸词**（「癌」「手术」「抑郁」「焦虑」）而不是只用长变体
#:    （「癌症」「抑郁症」）：家属说的是「肺癌」「要不要做手术」，
#:    长变体匹配不上就等于没拦。
MEDICAL_INTENT_KEYWORDS: tuple[str, ...] = (
    # 诊断与病情
    "诊断", "确诊", "得了什么病", "什么病", "是不是得了", "严重吗", "什么症状",
    "病情判断", "病因", "会不会是",
    # 用药
    "吃什么药", "该吃什么药", "吃什么药比较好", "能吃吗", "能不能吃", "能服用吗",
    "剂量", "用量", "一天几次", "一天吃几次", "吃几次", "几天一次",
    "饭前还是饭后", "副作用", "禁忌", "过量",
    "换药", "停药", "加量", "减量", "处方", "开药", "配药",
    # 疾病与治疗
    "肿瘤", "癌", "化疗", "放疗", "支架", "手术", "偏方", "保健品有用吗",
    "高血压", "糖尿病", "冠心病", "脑梗", "中风", "抑郁", "焦虑", "失眠",
    # 危急信号
    "自杀", "自残", "遗嘱", "传染",
)

#: 派单措辞禁用词 —— 与 ADR-0016 决策 4.4 一致。
#:
#: 前端 ``frontend/src/__tests__/matching.test.js`` 已经断言过这三个词，
#: 助手输出同样不得出现。
ASSIGNMENT_FORBIDDEN_WORDS: tuple[str, ...] = (
    "已指派", "已分配", "自动派单",
)

#: 派单词的纠正映射（词语级纠正，保留其余内容）。
ASSIGNMENT_WORD_FIXES: dict[str, str] = {
    "已指派": "推荐",
    "已分配": "推荐",
    "自动派单": "自动推荐",
}


# ──────────────────────────────────────────────────────────────────────
# 结果类型
# ──────────────────────────────────────────────────────────────────────


class IllegalQuestion(Exception):
    """问题内容命中辱骂 / 违规引流词 —— 对应后端错误码 ``10003``。"""

    def __init__(self, hit: str) -> None:
        super().__init__(f"问题内容包含违规表述：{hit}")
        self.hit = hit


@dataclass(frozen=True)
class InputVerdict:
    """输入侧判定结果。"""

    #: 是否命中医疗意图（命中则直接拒答，不经 LLM）
    medical: bool = False
    #: 命中的医疗关键词（第一个）
    medical_hit: str | None = None
    #: 命中的辱骂 / 引流词（第一个）；非空时调用方应抛 ``IllegalQuestion``
    abusive_hit: str | None = None


@dataclass(frozen=True)
class OutputScan:
    """输出侧扫描结果。"""

    #: 扫描后的文本（可能被整段替换或词语纠正）
    text: str
    #: 是否被拦下
    blocked: bool = False
    #: 命中的词（第一个）
    hit: str | None = None
    #: 拦截类型：``medical`` / ``assignment`` / None
    kind: str | None = None


# ──────────────────────────────────────────────────────────────────────
# 词表访问器
# ──────────────────────────────────────────────────────────────────────


def medical_advice_words() -> tuple[str, ...]:
    """共享的医疗建议禁用词表。"""
    return _MEDICAL_ADVICE_WORDS


def sensitive_words() -> tuple[str, ...]:
    """共享的辱骂 / 违规引流词表。"""
    return _SENSITIVE_WORDS


#: 保留旧名 —— 语义未变（都是「辱骂 / 违规引流词」），但**来源已改为共享文件**。
ABUSIVE_KEYWORDS: tuple[str, ...] = _SENSITIVE_WORDS


# ──────────────────────────────────────────────────────────────────────
# 第 1 层：输入侧
# ──────────────────────────────────────────────────────────────────────


def find_medical_intent(text: str) -> str | None:
    """粗判是否属于医疗意图。命中返回关键词，未命中返回 ``None``。"""
    if not text:
        return None
    for word in MEDICAL_INTENT_KEYWORDS:
        if word in text:
            return word
    return None


def find_abusive(text: str) -> str | None:
    """查找辱骂 / 违规引流词。命中返回该词。"""
    if not text:
        return None
    for word in _SENSITIVE_WORDS:
        if word in text:
            return word
    return None


def scan_input(text: str) -> InputVerdict:
    """第 1 层护栏。

    :raises IllegalQuestion: 命中辱骂 / 违规引流词。
    """
    abusive_hit = find_abusive(text)
    if abusive_hit:
        raise IllegalQuestion(abusive_hit)
    medical_hit = find_medical_intent(text)
    return InputVerdict(medical=medical_hit is not None, medical_hit=medical_hit)


# ──────────────────────────────────────────────────────────────────────
# 第 3 层：输出侧
# ──────────────────────────────────────────────────────────────────────


def find_assignment_word(text: str) -> str | None:
    """查找派单措辞禁用词。命中返回该词。"""
    if not text:
        return None
    for word in ASSIGNMENT_FORBIDDEN_WORDS:
        if word in text:
            return word
    return None


def scan_output(text: str) -> OutputScan:
    """第 3 层护栏。

    - 命中**共享禁用词表** → 整段替换为 :data:`REFUSAL_TEMPLATE`；
    - 命中**派单措辞** → 词语级纠正 + 追加 :data:`RECOMMEND_DISCLAIMER`；
    - 文本为空 → 原样返回（调用方负责回落到 FAQ 原文）。
    """
    if not text:
        return OutputScan(text=text)

    # 医疗内容：整段拒答
    for word in _MEDICAL_ADVICE_WORDS:
        if word in text:
            return OutputScan(
                text=REFUSAL_TEMPLATE, blocked=True, hit=word, kind="medical"
            )

    # 派单措辞：词语级纠正
    hit = find_assignment_word(text)
    if hit:
        corrected = text
        for bad, good in ASSIGNMENT_WORD_FIXES.items():
            corrected = corrected.replace(bad, good)
        if RECOMMEND_DISCLAIMER not in corrected:
            corrected = f"{corrected}\n\n{RECOMMEND_DISCLAIMER}"
        return OutputScan(
            text=corrected, blocked=True, hit=hit, kind="assignment"
        )

    return OutputScan(text=text)


__all__ = [
    "ABUSIVE_KEYWORDS",
    "ASSIGNMENT_FORBIDDEN_WORDS",
    "ASSIGNMENT_WORD_FIXES",
    "CLARIFY_TEMPLATE",
    "ComplianceWordsError",
    "IllegalQuestion",
    "InputVerdict",
    "MEDICAL_INTENT_HINT",
    "MEDICAL_INTENT_KEYWORDS",
    "OutputScan",
    "RECOMMEND_DISCLAIMER",
    "REFUSAL_TEMPLATE",
    "find_abusive",
    "find_assignment_word",
    "find_medical_intent",
    "medical_advice_words",
    "sensitive_words",
    "scan_input",
    "scan_output",
]
