package org.company.nianglin.service.support.medication;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 升级时点策略单测（V5 · 漏服多级升级）。
 *
 * <p>盯三件事：<b>基准时刻</b>是「本该被判漏服的时刻」（plan_time + 阈值）而不是
 * 状态变更时间；<b>边界值</b>（30 分钟 / 2 小时 / 24 小时整点）按「达到即升级」；
 * <b>阈值参与计算</b> —— 阈值是运营参数，改了阈值整条阶梯跟着平移。</p>
 *
 * @author 银龄伴诊团队
 * @since V5
 */
@DisplayName("漏服升级时点：基准 = plan_time + 阈值")
class ReminderLevelPolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 12, 0);
    private static final int THRESHOLD = 60;

    @Test
    @DisplayName("未判漏服 → 0 级；已判漏服但未满 30 分钟 → 仍是 0 级")
    void dueLevelShouldBeNoneRightAfterMissed() {
        // plan_time = 10:30，阈值 60 分钟 → 11:00 判漏服，现在 12:00 已过 60 分钟 → 1 级
        assertEquals(1, ReminderLevelPolicy.dueLevel(NOW.minusMinutes(90), THRESHOLD, NOW));

        // plan_time = 11:45 → 12:45 才判漏服，现在还没到点 → 0 级
        assertEquals(0, ReminderLevelPolicy.dueLevel(NOW.minusMinutes(15), THRESHOLD, NOW));
    }

    @Test
    @DisplayName("30 分钟 / 2 小时 / 24 小时整点均按「达到即升级」")
    void dueLevelShouldEscalateAtExactBoundaries() {
        // 恰好 30 分钟：missedAt = now - 30min → 1 级
        assertEquals(1, ReminderLevelPolicy.dueLevel(NOW.minusMinutes(90), THRESHOLD, NOW));
        // 恰好 2 小时：missedAt = now - 120min → 2 级
        assertEquals(2, ReminderLevelPolicy.dueLevel(NOW.minusMinutes(180), THRESHOLD, NOW));
        // 恰好 24 小时：missedAt = now - 1440min → 3 级
        assertEquals(3, ReminderLevelPolicy.dueLevel(NOW.minusMinutes(1500), THRESHOLD, NOW));
        // 差 1 分钟不到 2 小时 → 仍是 1 级
        assertEquals(1, ReminderLevelPolicy.dueLevel(NOW.minusMinutes(179), THRESHOLD, NOW));
    }

    @Test
    @DisplayName("阈值参与计算：阈值调大，整条阶梯跟着后移")
    void dueLevelShouldHonorThresholdShift() {
        // plan_time = now - 90min，阈值 120 分钟 → missedAt = now + 30min，还没判漏服 → 0 级
        assertEquals(0, ReminderLevelPolicy.dueLevel(NOW.minusMinutes(90), 120, NOW));
        // 同一条任务，阈值回到 60 → 40 分钟前已判漏服 → 1 级
        assertEquals(1, ReminderLevelPolicy.dueLevel(NOW.minusMinutes(90), 60, NOW));
    }

    @Test
    @DisplayName("空 plan_time 防御性返回 0 —— 不因脏数据乱升级")
    void dueLevelShouldBeNoneWhenPlanTimeMissing() {
        assertEquals(0, ReminderLevelPolicy.dueLevel(null, THRESHOLD, NOW));
    }
}
