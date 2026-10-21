package org.company.nianglin.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V3.7 智能助手配置的<b>启动期校验</b>单测。
 *
 * <h3>为什么这类校验值得单测</h3>
 *
 * <p>与 {@code MatchingWeightConfigTest} 同一个道理：这些值配错<b>不会报错</b>，
 * 只会让助手「安静地不好用」——
 * 超时太短 → 长回答随机失败；限流阈值配成 0 → 助手对所有人不可用；
 * FAQ 路径指错 → 降级路径失效。所以约定是「启动失败」。</p>
 *
 * <p>每个「配置写坏」的用例都断言<b>具体的失败原因</b>（消息里含配置键名），
 * 只断言「抛了异常」的话，把校验顺序改错了也发现不了。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.7
 */
@DisplayName("V3.7 智能助手配置：不合理即启动失败")
class AssistantPropertiesTest {

    @Test
    @DisplayName("默认配置通过启动校验")
    void defaultShouldPassValidation() {
        AssistantProperties properties = new AssistantProperties();

        assertDoesNotThrow(properties::validate, "默认配置必须能通过启动校验");
        assertEquals(10, properties.getRateLimitPerMinute());
        assertEquals(60000, properties.getReadTimeoutMs());
        assertEquals(3000, properties.getConnectTimeoutMs());
        assertEquals(6, properties.getMaxHistoryTurns());
        assertEquals(500, properties.getMaxQuestionLength());
        assertTrue(properties.isEnabled());
        assertTrue(properties.isFallbackOnError());
    }

    @Test
    @DisplayName("限流阈值配成 0 → 启动失败（否则助手对所有人不可用）")
    void zeroRateLimitShouldFail() {
        AssistantProperties properties = new AssistantProperties();
        properties.setRateLimitPerMinute(0);

        IllegalStateException e = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(e.getMessage().contains("rate-limit-per-minute"),
                "失败消息应指明配置键名，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("读超时小于 1000ms → 启动失败")
    void tooSmallReadTimeoutShouldFail() {
        AssistantProperties properties = new AssistantProperties();
        properties.setReadTimeoutMs(500);

        IllegalStateException e = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(e.getMessage().contains("read-timeout-ms"),
                "失败消息应指明配置键名，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("连接超时小于 100ms → 启动失败")
    void tooSmallConnectTimeoutShouldFail() {
        AssistantProperties properties = new AssistantProperties();
        properties.setConnectTimeoutMs(10);

        IllegalStateException e = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(e.getMessage().contains("connect-timeout-ms"),
                "失败消息应指明配置键名，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("历史轮数为负 → 启动失败")
    void negativeHistoryTurnsShouldFail() {
        AssistantProperties properties = new AssistantProperties();
        properties.setMaxHistoryTurns(-1);

        IllegalStateException e = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(e.getMessage().contains("max-history-turns"),
                "失败消息应指明配置键名，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("问题长度上限为 0 → 启动失败")
    void zeroQuestionLengthShouldFail() {
        AssistantProperties properties = new AssistantProperties();
        properties.setMaxQuestionLength(0);

        IllegalStateException e = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(e.getMessage().contains("max-question-length"),
                "失败消息应指明配置键名，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("启用时服务地址为空 → 启动失败")
    void blankServiceUrlShouldFail() {
        AssistantProperties properties = new AssistantProperties();
        properties.setServiceUrl("  ");

        IllegalStateException e = assertThrows(IllegalStateException.class, properties::validate);
        assertTrue(e.getMessage().contains("service-url"),
                "失败消息应指明配置键名，实际：" + e.getMessage());
    }

    @Test
    @DisplayName("助手关闭时允许服务地址为空（整个模块不启用）")
    void disabledAllowsBlankServiceUrl() {
        AssistantProperties properties = new AssistantProperties();
        properties.setEnabled(false);
        properties.setServiceUrl("");

        assertDoesNotThrow(properties::validate, "关闭时不该因为地址为空而启动失败");
    }
}
