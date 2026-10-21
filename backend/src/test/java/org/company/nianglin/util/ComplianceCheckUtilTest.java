package org.company.nianglin.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 合规词表单测（V3.7 · ADR-0020 决策 3）。
 *
 * <h3>本测试存在的理由：词表现在是外部资源了</h3>
 *
 * <p>词表移出代码（{@code resources/compliance/medical-advice-words.txt}）是为了让
 * Java 与 Python 读同一份 —— 但「读同一份」不等于「两边理解一致」。
 * 如果有人只改了文件、忘了同步另一侧的语义理解，护栏就会在两边分叉。
 * 所以这里冻结一份<b>17 词的精确快照</b>：词表增删改，本测试即红。</p>
 *
 * <p>Python 侧 {@code ai-service/tests/test_guard.py} 冻结同一份快照 ——
 * 两边的快照必须逐字相同，否则就是漂移。</p>
 */
class ComplianceCheckUtilTest {

    /**
     * 合规词表的精确快照 —— 与 {@code resources/compliance/medical-advice-words.txt} 逐字一致。
     *
     * <p>⚠️ 改词表时这里必须同步改，且 Python 侧 {@code test_guard.py} 的同名快照也要改。
     * 只改一边 → 两边分叉 → 护栏行为不一致。</p>
     */
    private static final List<String> EXPECTED_WORDS = List.of(
            // 诊断结论
            "诊断", "确诊", "疑似", "病情判断",
            // 开药动作
            "处方", "开具", "开药", "配药", "建议服用", "建议使用",
            // 用药调整
            "换药", "停药", "加量", "减量", "剂量", "服用方法", "用药方案");

    @Test
    @DisplayName("词表快照：恰好 17 个词，顺序与内容与资源文件一致")
    void wordSnapshotIsFrozen() {
        assertThat(ComplianceCheckUtil.words())
                .containsExactlyElementsOf(EXPECTED_WORDS)
                .hasSize(17);
    }

    @ParameterizedTest(name = "命中「{0}」")
    @ValueSource(strings = {
            "医生建议服用阿司匹林，每天一次",
            "这个病确诊了",
            "疑似感冒引起的",
            "病情判断如下",
            "已经开具处方了",
            "帮忙开药",
            "去配药",
            "建议服用布洛芬",
            "建议使用胰岛素",
            "需要换药了",
            "先停药观察",
            "可以加量吗",
            "要不要减量",
            "这个剂量合适吗",
            "服用方法是饭前",
            "用药方案我记下来了"
    })
    @DisplayName("17 个词逐一命中（逐条锁死，不能少一个）")
    void everyWordHits(String text) {
        assertThat(ComplianceCheckUtil.containsMedicalAdvice(text)).isTrue();
        assertThat(ComplianceCheckUtil.firstHit(text)).isNotNull();
    }

    @Test
    @DisplayName("firstHit 返回的是实际命中的那个词，不是笼统的 true")
    void firstHitReturnsTheConcreteWord() {
        assertThat(ComplianceCheckUtil.firstHit("医生说建议服用阿司匹林")).isEqualTo("建议服用");
        assertThat(ComplianceCheckUtil.firstHit("已协助取药")).isNull();
    }

    @ParameterizedTest(name = "放行「{0}」")
    @ValueSource(strings = {
            "已协助取药",
            "陪同老人完成心电图检查",
            "已协助取药，家属已签收",
            "老人今天精神状态不错",
            "已送达医院窗口并协助挂号"
    })
    @DisplayName("合法的过程记录不得被误伤")
    void legalRecordsPass(String text) {
        assertThat(ComplianceCheckUtil.firstHit(text)).isNull();
        assertThat(ComplianceCheckUtil.containsMedicalAdvice(text)).isFalse();
    }

    @Test
    @DisplayName("空值与空串不命中，且不抛异常")
    void nullAndEmptyAreSafe() {
        assertThat(ComplianceCheckUtil.firstHit(null)).isNull();
        assertThat(ComplianceCheckUtil.firstHit("")).isNull();
        assertThat(ComplianceCheckUtil.containsMedicalAdvice(null)).isFalse();
    }

    @Test
    @DisplayName("词表资源必须存在于 classpath —— 读不到就启动失败，不允许空词表静默放行")
    void resourceIsOnClasspath() {
        assertThat(ComplianceCheckUtil.class.getResourceAsStream(
                "/compliance/medical-advice-words.txt")).isNotNull();
    }
}
