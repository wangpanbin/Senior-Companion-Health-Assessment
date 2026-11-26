package org.company.nianglin.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 产品反馈状态机单测（V6）。
 *
 * <p>对应 {@code docs/api/15-product-feedback.md} §一「状态机」。</p>
 *
 * <h3>为什么单独给这张四态图写一套测试</h3>
 *
 * <p>它看起来和 {@code ComplaintStatus} 一模一样，正因为一样才容易在改动时被顺手「简化」
 * （「两态就够了」）。这几条断言锁住的是<b>不可回退</b>与<b>不可跳级</b>这两条业务承诺：
 * 一条建议被「未采纳」之后，任何人都不该能把它拖回「处理中」，
 * 也不该在没人接手的情况下直接结案 —— 后者等于允许「看都没看就关掉」。</p>
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
@DisplayName("产品反馈状态机")
class FeedbackStatusTest {

    @Test
    @DisplayName("正向流转：待处理 → 处理中 → 已采纳 / 未采纳")
    void forwardTransitionShouldBeAllowed() {
        assertTrue(FeedbackStatus.PENDING.canTransitTo(FeedbackStatus.PROCESSING));
        assertTrue(FeedbackStatus.PROCESSING.canTransitTo(FeedbackStatus.RESOLVED));
        assertTrue(FeedbackStatus.PROCESSING.canTransitTo(FeedbackStatus.REJECTED));
    }

    @Test
    @DisplayName("禁止跳级：待处理不能直接到已采纳 / 未采纳")
    void skipShouldBeRejected() {
        assertFalse(FeedbackStatus.PENDING.canTransitTo(FeedbackStatus.RESOLVED));
        assertFalse(FeedbackStatus.PENDING.canTransitTo(FeedbackStatus.REJECTED));
    }

    @Test
    @DisplayName("禁止回退：任何终态都回不去，处理中也回不到待处理")
    void rollbackShouldBeRejected() {
        assertFalse(FeedbackStatus.PROCESSING.canTransitTo(FeedbackStatus.PENDING));
        assertFalse(FeedbackStatus.RESOLVED.canTransitTo(FeedbackStatus.PROCESSING));
        assertFalse(FeedbackStatus.REJECTED.canTransitTo(FeedbackStatus.PROCESSING));
    }

    @Test
    @DisplayName("终态不可再正向流转（含两个终态之间互转）")
    void terminalStatusShouldNotTransit() {
        for (FeedbackStatus target : FeedbackStatus.values()) {
            assertFalse(FeedbackStatus.RESOLVED.canTransitTo(target));
            assertFalse(FeedbackStatus.REJECTED.canTransitTo(target));
        }
    }

    @Test
    @DisplayName("终态判定：只有已采纳 / 未采纳是终态")
    void terminalFlag() {
        assertTrue(FeedbackStatus.RESOLVED.isTerminal());
        assertTrue(FeedbackStatus.REJECTED.isTerminal());
        assertFalse(FeedbackStatus.PENDING.isTerminal());
        assertFalse(FeedbackStatus.PROCESSING.isTerminal());
    }

    @Test
    @DisplayName("目标状态为 null 时应安全返回 false")
    void nullTargetShouldBeSafe() {
        assertFalse(FeedbackStatus.PENDING.canTransitTo(null));
    }

    @Test
    @DisplayName("按名称 / 中文标签反查")
    void shouldResolveByNameOrLabel() {
        assertEquals(FeedbackStatus.PENDING, FeedbackStatus.of("PENDING"));
        assertEquals(FeedbackStatus.PENDING, FeedbackStatus.of("pending"));
        assertEquals(FeedbackStatus.PENDING, FeedbackStatus.of("待处理"));
        assertEquals(FeedbackStatus.RESOLVED, FeedbackStatus.of("已采纳"));
        assertNull(FeedbackStatus.of("不存在"));
        assertNull(FeedbackStatus.of(null));
    }

    @Test
    @DisplayName("中文展示名：非法值原样返回，不抛异常")
    void labelOfShouldFallBackToRawValue() {
        assertEquals("处理中", FeedbackStatus.labelOf("PROCESSING"));
        assertEquals("脏数据", FeedbackStatus.labelOf("脏数据"));
    }

    @Test
    @DisplayName("按名称反查时大小写不敏感（'resolved' 与 'RESOLVED' 等价）")
    void ofIsCaseInsensitive() {
        assertEquals(FeedbackStatus.RESOLVED, FeedbackStatus.of("resolved"));
    }

    @Test
    @DisplayName("分类枚举：四个取值齐备且可反查（前端下拉与后端枚举必须一一对应）")
    void feedbackTypeShouldCoverAllFourValues() {
        assertEquals(4, FeedbackType.values().length);
        assertEquals(FeedbackType.FEATURE, FeedbackType.of("feature"));
        assertEquals(FeedbackType.EXPERIENCE, FeedbackType.of("EXPERIENCE"));
        assertEquals(FeedbackType.DEFECT, FeedbackType.of("DEFECT"));
        assertEquals(FeedbackType.OTHER, FeedbackType.of("OTHER"));
        assertNull(FeedbackType.of("PRODUCT"));
        assertNull(FeedbackType.of(null));
        assertEquals("功能建议", FeedbackType.labelOf("FEATURE"));
    }
}
