"""护栏测试（V3.7 · ADR-0020 决策 2 与 3）。

这是本版**最不能出错**的一组测试。护栏失效不会报错，只会静默放行，
所以这里的每一条断言都对应一个具体的失效模式。
"""

from __future__ import annotations

import pytest

from app import guard
from app.paths import (
    ComplianceWordsError,
    load_compliance_words,
    parse_word_lines,
)

#: 共享词表的**精确快照**。
#:
#: ⚠️ 与 ``backend/src/test/java/.../ComplianceCheckUtilTest.java`` 里的同名快照
#: **必须逐字相同** —— 两边不一致就是词表漂移。
#:
#: 词数由 :func:`test_word_snapshot_is_frozen` 程序化数出，不靠手算。
EXPECTED_WORDS = (
    # 诊断结论
    "诊断", "确诊", "疑似", "病情判断",
    # 开药动作
    "处方", "开具", "开药", "配药", "建议服用", "建议使用",
    # 用药调整
    "换药", "停药", "加量", "减量", "剂量", "服用方法", "用药方案",
)


# ──────────────────────────────────────────────────────────────────────
# 词表加载
# ──────────────────────────────────────────────────────────────────────


def test_word_snapshot_is_frozen(medical_words: tuple[str, ...]) -> None:
    """词表快照：与共享资源文件逐字一致。词表增删改 → 本测试即红。"""
    assert medical_words == EXPECTED_WORDS
    assert len(medical_words) == 17


def test_sensitive_words_snapshot_is_frozen() -> None:
    """辱骂 / 违规引流词表**也是共享的**（V3.7 抽出），冻结一份快照。

    与 Java 侧 ``SensitiveWordUtilTest`` 的同名快照必须逐字相同。
    """
    assert guard.sensitive_words() == (
        # 人身攻击
        "垃圾", "滚蛋", "骗子", "骗人", "去死", "废物", "弱智", "傻逼", "脑残",
        # 违规引流
        "加微信", "加我微信", "私聊转账", "私下转账", "扫码付款",
    )


def test_refusal_template_comes_from_shared_file() -> None:
    """拒答模板来自**共享资源文件**（与 Java 侧同一份），两侧不会各说各话。"""
    from app.paths import load_refusal_template, resolve_refusal_template_path

    assert resolve_refusal_template_path().is_file()
    assert guard.REFUSAL_TEMPLATE == load_refusal_template()


def test_parse_word_lines_ignores_comments_and_blanks() -> None:
    text = "# 注释\n诊断\n\n  确诊  \n# 又一条注释\n疑似\n"
    assert parse_word_lines(text) == ["诊断", "确诊", "疑似"]


def test_missing_words_file_raises(tmp_path) -> None:
    """读不到词表 → 抛错（服务启动失败）。**绝不能静默返回空词表。**"""
    with pytest.raises(ComplianceWordsError):
        load_compliance_words(tmp_path / "nope.txt")


def test_too_few_words_raises(tmp_path) -> None:
    """词数不足 → 抛错。词表被裁剪过的场景要立刻暴露。"""
    partial = tmp_path / "words.txt"
    partial.write_text("诊断\n确诊\n", encoding="utf-8")
    with pytest.raises(ComplianceWordsError):
        load_compliance_words(partial)


def test_missing_required_keyword_raises(tmp_path) -> None:
    """缺少「建议服用」「剂量」→ 抛错。多半是读错了文件。"""
    wrong = tmp_path / "words.txt"
    wrong.write_text("\n".join(["诊断", "确诊", "疑似", "病情判断"] * 3), encoding="utf-8")
    with pytest.raises(ComplianceWordsError):
        load_compliance_words(wrong)


# ──────────────────────────────────────────────────────────────────────
# 第 1 层：输入侧
# ──────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "question",
    [
        "我爸这个病严重吗",
        "这该吃什么药",
        "一天吃几次",
        "这个剂量够吗",
        "要不要停药",
        "能帮我诊断一下吗",
        "是不是确诊了",
        "高血压要怎么办",
        "保健品有用吗",
    ],
)
def test_medical_intent_detected(question: str) -> None:
    """医疗意图 → 判定为医疗意图。"""
    verdict = guard.scan_input(question)
    assert verdict.medical is True
    assert verdict.medical_hit


@pytest.mark.parametrize(
    "question",
    [
        "要不要做这个手术",
        "肺癌怎么办",
        "我最近很抑郁",
        "焦虑得睡不着",
    ],
)
def test_medical_intent_covers_bare_terms(question: str) -> None:
    """**裸词**也要拦得住。

    家属说的是「肺癌」「要不要做手术」「我抑郁了」，
    而词表若只写「癌症」「抑郁症」就匹配不上 —— 漏拦等于没拦。
    """
    verdict = guard.scan_input(question)
    assert verdict.medical is True, f"「{question}」未被识别为医疗意图"


@pytest.mark.parametrize(
    "question",
    [
        "陪诊费多少钱",
        "下了单还能取消吗",
        "我的订单到哪了",
        "帮我找个陪诊员",
    ],
)
def test_platform_questions_not_flagged_medical(question: str) -> None:
    """平台规则类问题**不得**被误判成医疗意图 —— 否则家属问啥都被拒。"""
    verdict = guard.scan_input(question)
    assert verdict.medical is False


@pytest.mark.parametrize(
    "question",
    ["你是骗子", "加我微信私下转账", "扫码付款吧"],
)
def test_abusive_question_raises(question: str) -> None:
    """辱骂 / 违规引流 → 抛 IllegalQuestion（后端转 10003）。"""
    with pytest.raises(guard.IllegalQuestion):
        guard.scan_input(question)


def test_empty_input_is_safe() -> None:
    verdict = guard.scan_input("")
    assert verdict.medical is False
    assert verdict.abusive_hit is None


# ──────────────────────────────────────────────────────────────────────
# 第 3 层：输出侧
# ──────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("word", EXPECTED_WORDS)
def test_every_forbidden_word_triggers_whole_replacement(word: str) -> None:
    """17 个词逐一命中 → **整段替换**为拒答模板。

    断言「原句不残留」：这是「命中即拒、不打码后放行」的可执行版本。
    """
    answer = f"医生说{word}，我照做就行。"
    scan = guard.scan_output(answer)
    assert scan.blocked is True
    assert scan.kind == "medical"
    assert scan.hit == word
    assert scan.text == guard.REFUSAL_TEMPLATE
    assert word not in scan.text


def test_legal_answer_passes_output_guard() -> None:
    """合法的过程记录不得被拦。"""
    answer = "陪诊员已陪同老人完成心电图检查，并协助取药。"
    scan = guard.scan_output(answer)
    assert scan.blocked is False
    assert scan.text == answer


def test_assignment_wording_is_corrected_not_dropped() -> None:
    """派单措辞 → 词语级纠正，**保留其余内容**（整段丢掉会让家属白问一次）。"""
    answer = "根据匹配结果，已指派李建军为您服务，评分 4.8。"
    scan = guard.scan_output(answer)
    assert scan.blocked is True
    assert scan.kind == "assignment"
    assert "已指派" not in scan.text
    assert "自动派单" not in scan.text
    assert "已分配" not in scan.text
    # 其余内容保留
    assert "李建军" in scan.text
    assert "4.8" in scan.text
    # 免责句被追加
    assert guard.RECOMMEND_DISCLAIMER in scan.text


@pytest.mark.parametrize(
    "word", ["已指派", "已分配", "自动派单"]
)
def test_all_assignment_words_are_blocked(word: str) -> None:
    """三个派单词逐一拦下（与前端 matching.test.js 同源断言）。"""
    scan = guard.scan_output(f"系统{word}了李建军。")
    assert scan.blocked is True
    assert scan.kind == "assignment"
    assert word not in scan.text


# ──────────────────────────────────────────────────────────────────────
# 距离措辞 —— ADR-0016 决策 3 的代码层兜底
# ──────────────────────────────────────────────────────────────────────


def test_distance_wording_is_corrected_to_truthful_form() -> None:
    """「离得近」→ 纠正成「距离维度无法计算」，而不是笼统免责。

    陪诊员坐标全是空，距离维度恒为 0 —— 把「算不出来」说成「很近」，
    正在挑陪诊员的家属会据此做决定（ADR-0016 决策 3）。
    """
    answer = "这位陪诊员离得近，接诊方便。"
    scan = guard.scan_output(answer)
    assert scan.blocked is True
    assert scan.kind == "distance"
    assert "离得近" not in scan.text
    assert "距离维度无法计算" in scan.text
    # 其余内容保留
    assert "接诊方便" in scan.text


@pytest.mark.parametrize("phrase", guard.DISTANCE_FORBIDDEN_PHRASES)
def test_all_distance_phrases_are_blocked(phrase: str) -> None:
    """每一个距离措辞禁用词都要拦下（prompt 约束 5 与 PRD 验收条款的并集）。"""
    scan = guard.scan_output(f"这位陪诊员{phrase}，推荐给您。")
    assert scan.blocked is True
    assert scan.kind == "distance"
    assert phrase not in scan.text
    assert "距离维度无法计算" in scan.text


def test_distance_replacement_does_not_trigger_the_guard_again() -> None:
    """纠正后的串**自身不含任何禁用词**。

    否则会出现「纠正出来的文本又被自己拦下」的死循环。
    """
    for phrase in guard.DISTANCE_FORBIDDEN_PHRASES:
        assert phrase not in guard.DISTANCE_REPLACEMENT
    # 纠正一次之后不再命中
    corrected = f"这位陪诊员{guard.DISTANCE_FORBIDDEN_PHRASES[0]}，推荐给您。"
    once = guard.scan_output(corrected)
    twice = guard.scan_output(once.text)
    assert twice.blocked is False
    assert twice.text == once.text


def test_assignment_and_distance_are_both_corrected() -> None:
    """两种措辞**同时**出现时都要纠正 —— 不能先命中一种就早退。

    「已指派…就在附近」是最坏组合：两个错误叠加，
    早退会让距离那一半漏出去。
    """
    answer = "系统已指派李建军，他就在附近。"
    scan = guard.scan_output(answer)
    assert scan.blocked is True
    assert "已指派" not in scan.text
    assert "就在附近" not in scan.text
    assert "距离维度无法计算" in scan.text
    assert guard.RECOMMEND_DISCLAIMER in scan.text


def test_distance_notes_text_is_not_blocked() -> None:
    """降级说明原文「未填写坐标，距离维度记 0」**不得**被误伤。

    它含「距离」但不含任何禁用措辞 —— 拦下它等于把真相也拦了。
    """
    answer = "命中理由：服务区域匹配。降级说明：未填写坐标，距离维度记 0。"
    scan = guard.scan_output(answer)
    assert scan.blocked is False
    assert scan.text == answer


# ──────────────────────────────────────────────────────────────────────
# 固定文案的自检 —— 一个容易踩、且极难排查的坑
# ──────────────────────────────────────────────────────────────────────


def test_refusal_template_contains_no_forbidden_word(medical_words: tuple[str, ...]) -> None:
    """**拒答模板本身不得含禁用词。**

    否则输出护栏会把拒答模板自己拦下，家属看到一段莫名其妙的提示。
    """
    for word in medical_words:
        assert word not in guard.REFUSAL_TEMPLATE, f"拒答模板含有禁用词：{word}"


def test_recommend_disclaimer_contains_no_forbidden_word(
    medical_words: tuple[str, ...],
) -> None:
    """推荐免责句同样不得含禁用词。"""
    for word in medical_words:
        assert word not in guard.RECOMMEND_DISCLAIMER, f"免责句含有禁用词：{word}"


def test_clarify_template_contains_no_forbidden_word(
    medical_words: tuple[str, ...],
) -> None:
    """澄清话术同样不得含禁用词。"""
    for word in medical_words:
        assert word not in guard.CLARIFY_TEMPLATE, f"澄清话术含有禁用词：{word}"


def test_medical_intent_hint_contains_no_forbidden_word(
    medical_words: tuple[str, ...],
) -> None:
    """医疗意图提示句同样不得含禁用词。"""
    for word in medical_words:
        assert word not in guard.MEDICAL_INTENT_HINT, f"提示句含有禁用词：{word}"
