package org.company.nianglin.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 实时位置配置的启动期校验单测（ADR-0022）。
 *
 * <p>这组测试保护的是同一类产品级不变量：<b>错误配置必须在启动时就失败</b>。
 * 五个值的每一种配错都<b>不会</b>报错，只会让地图安静地不对：</p>
 *
 * <ul>
 *   <li>{@code min-move-meters = 0} → 每一帧都落库，`companion_track` 一天涨几十万行；</li>
 *   <li>{@code latest-ttl-seconds = 1} → 最新点在下次上报前就过期，家属永远拿到空位置；</li>
 *   <li>{@code stale-threshold-seconds < min-report-interval-seconds} → 每次正常上报之间，
 *       界面都在喊「位置已停止更新」。</li>
 * </ul>
 *
 * <p>三种症状都表现为「地图看着没毛病，就是不动」，事后定位成本极高 ——
 * 所以让它在启动的十秒内就暴露（与 {@code MatchingProperties} /
 * {@code FulfillmentProperties} 同一口径）。</p>
 *
 * @author 银龄伴诊团队
 * @since ADR-0022
 */
@DisplayName("实时位置配置：启动期自洽校验")
class TrackingPropertiesTest {

    private TrackingProperties props(int interval, int move, int record, int ttl, int stale) {
        TrackingProperties p = new TrackingProperties();
        p.setMinReportIntervalSeconds(interval);
        p.setMinMoveMeters(move);
        p.setMinRecordIntervalSeconds(record);
        p.setLatestTtlSeconds(ttl);
        p.setStaleThresholdSeconds(stale);
        return p;
    }

    @Test
    @DisplayName("缺省值（10 / 50 / 120 / 300 / 180）通过校验")
    void defaultsShouldBeValid() {
        assertDoesNotThrow(() -> new TrackingProperties().validate());
    }

    @Test
    @DisplayName("合法自定义值通过校验")
    void validCustomValuesShouldPass() {
        assertDoesNotThrow(() -> props(5, 20, 60, 180, 90).validate());
        // stale 恰好等于上报间隔是下界：不算错，只是更早开始提示
        assertDoesNotThrow(() -> props(30, 50, 120, 300, 30).validate());
    }

    @Test
    @DisplayName("任一项非正 → 启动失败（0 与负数都算）")
    void nonPositiveValuesShouldFailFast() {
        assertThrows(IllegalStateException.class, () -> props(0, 50, 120, 300, 180).validate());
        assertThrows(IllegalStateException.class, () -> props(10, 0, 120, 300, 180).validate());
        assertThrows(IllegalStateException.class, () -> props(10, 50, 0, 300, 180).validate());
        assertThrows(IllegalStateException.class, () -> props(10, 50, 120, 0, 180).validate());
        assertThrows(IllegalStateException.class, () -> props(10, 50, 120, 300, 0).validate());
        assertThrows(IllegalStateException.class, () -> props(-1, 50, 120, 300, 180).validate());
    }

    @Test
    @DisplayName("stale 阈值小于上报间隔 → 启动失败（否则每次正常上报之间都在喊「停止更新」）")
    void staleThresholdBelowIntervalShouldFailFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> props(30, 50, 120, 300, 10).validate());
        assertTrue(ex.getMessage().contains("stale-threshold-seconds"), ex.getMessage());
    }

    @Test
    @DisplayName("最新点 TTL 不大于上报间隔 → 启动失败（否则家属永远拿不到位置）")
    void ttlNotGreaterThanIntervalShouldFailFast() {
        IllegalStateException equal = assertThrows(IllegalStateException.class,
                () -> props(30, 50, 120, 30, 180).validate());
        assertTrue(equal.getMessage().contains("latest-ttl-seconds"), equal.getMessage());

        IllegalStateException less = assertThrows(IllegalStateException.class,
                () -> props(30, 50, 120, 10, 180).validate());
        assertTrue(less.getMessage().contains("latest-ttl-seconds"), less.getMessage());
    }
}
