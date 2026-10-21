package org.company.nianglin.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 敏感词表单测（V3.7 · ADR-0020 决策 3）。
 *
 * <h3>本测试存在的理由：词表现在是外部资源了</h3>
 *
 * <p>词表移出代码（{@code resources/compliance/sensitive-words.txt}）是为了让
 * Java 与 Python 读同一份 —— 但「读同一份」不等于「两边理解一致」。
 * 如果有人只改了文件、忘了同步另一侧的语义理解，护栏就会在两边分叉。
 * 所以这里冻结一份<b>精确快照</b>：词表增删改，本测试即红。</p>
 *
 * <p>Python 侧 {@code ai-service/tests/test_guard.py} 冻结同一份快照 ——
 * 两边的快照必须逐字相同，否则就是漂移。</p>
 */
class SensitiveWordUtilTest {

    /**
     * 敏感词表的精确快照 —— 与 {@code resources/compliance/sensitive-words.txt} 逐字一致。
     *
     * <p>⚠️ 改词表时这里必须同步改，且 Python 侧 {@code test_guard.py} 的同名快照也要改。</p>
     */
    private static final java.util.List<String> EXPECTED_WORDS = java.util.List.of(
            // 人身攻击
            "垃圾", "滚蛋", "骗子", "骗人", "去死", "废物", "弱智", "傻逼", "脑残",
            // 违规引流（平台外交易 / 私下联系）
            "加微信", "加我微信", "私聊转账", "私下转账", "扫码付款");

    @Test
    @DisplayName("词表快照：恰好 14 个词，顺序与内容与资源文件一致")
    void wordSnapshotIsFrozen() {
        assertThat(SensitiveWordUtil.words())
                .containsExactlyElementsOf(EXPECTED_WORDS)
                .hasSize(14);
    }

    @ParameterizedTest(name = "命中「{0}」")
    @ValueSource(strings = {
            "垃圾服务", "滚蛋", "骗子", "骗人", "去死", "废物", "弱智", "傻逼", "脑残",
            "加微信", "加我微信", "私聊转账", "私下转账", "扫码付款"
    })
    @DisplayName("14 个词逐一命中（逐条锁死，不能少一个）")
    void everyWordHits(String text) {
        assertThat(SensitiveWordUtil.containsSensitiveWord(text)).isTrue();
        assertThat(SensitiveWordUtil.firstHit(text)).isNotNull();
    }

    @ParameterizedTest(name = "放行「{0}」")
    @ValueSource(strings = {
            "陪诊员很专业，准时到场",
            "复诊流程很清楚，护士也耐心",
            "已协助取药，家属已签收"
    })
    @DisplayName("正常评价不得被误伤")
    void legalReviewsPass(String text) {
        assertThat(SensitiveWordUtil.firstHit(text)).isNull();
        assertThat(SensitiveWordUtil.containsSensitiveWord(text)).isFalse();
    }

    @Test
    @DisplayName("空值与空串不命中，且不抛异常")
    void nullAndEmptyAreSafe() {
        assertThat(SensitiveWordUtil.firstHit(null)).isNull();
        assertThat(SensitiveWordUtil.firstHit("")).isNull();
        assertThat(SensitiveWordUtil.containsSensitiveWord(null)).isFalse();
    }

    @Test
    @DisplayName("词表资源必须存在于 classpath —— 读不到就启动失败，不允许空词表静默放行")
    void resourceIsOnClasspath() {
        assertThat(SensitiveWordUtil.class.getResourceAsStream(
                "/compliance/sensitive-words.txt")).isNotNull();
    }
}
