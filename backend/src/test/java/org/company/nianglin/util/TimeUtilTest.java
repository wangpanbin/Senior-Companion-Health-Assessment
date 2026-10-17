package org.company.nianglin.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 等待时长计算单测（V3.1 · T4）。
 *
 * <p>本组的意义不在「算得对」——那是一行减法——而在<b>时间由参数传入</b>。
 * V3.1 票据把「等待分钟数随『现在』推进而增加」列为验收项，
 * 若实现里写死 {@code LocalDateTime.now()}，这条验收就只能靠 sleep 去验，
 * 于是没人验。本组给定两个 {@code now} 直接断言差值，等于把这条验收钉在编译期可跑的代码上。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@DisplayName("TimeUtil.elapsedMinutes：等待时长随时间推进而自增")
class TimeUtilTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 17, 9, 0, 0);

    /* ================================================================== */
    /* 核心：随「现在」推进                                                 */
    /* ================================================================== */

    @Test
    @DisplayName("同一下单时间，基准时刻每过 15 分钟，等待时长就多 15 分钟")
    void waitMinutesShouldGrowWithNow() {
        int at30 = TimeUtil.elapsedMinutes(T0, T0.plusMinutes(30));
        int at45 = TimeUtil.elapsedMinutes(T0, T0.plusMinutes(45));
        int at60 = TimeUtil.elapsedMinutes(T0, T0.plusMinutes(60));

        assertEquals(30, at30);
        assertEquals(45, at45);
        assertEquals(60, at60);
        assertTrue(at45 > at30 && at60 > at45, "等待时长必须严格随基准时刻单调递增");
    }

    /* ================================================================== */
    /* 边界                                                                */
    /* ================================================================== */

    @ParameterizedTest(name = "等待 {0} 分钟")
    @CsvSource({"0", "1", "9", "10", "29", "30", "119", "120", "1440"})
    @DisplayName("整分钟边界：恰好等于阈值分钟数时返回该值（截断而非四舍五入）")
    void exactMinuteBoundary(int minutes) {
        assertEquals(minutes, TimeUtil.elapsedMinutes(T0, T0.plusMinutes(minutes)));
    }

    @Test
    @DisplayName("不足一分钟记 0（截断，不是四舍五入）")
    void partialMinuteTruncatesToZero() {
        assertEquals(0, TimeUtil.elapsedMinutes(T0, T0.plusSeconds(59)));
        assertEquals(1, TimeUtil.elapsedMinutes(T0, T0.plusSeconds(60)));
    }

    @Test
    @DisplayName("下单时间晚于基准时刻（时钟漂移 / 人工改库）时钳到 0，不返回负数")
    void futureStartClampsToZero() {
        assertEquals(0, TimeUtil.elapsedMinutes(T0, T0.minusMinutes(3)));
    }

    @Test
    @DisplayName("极端久远的订单不溢出 int（钳到 Integer.MAX_VALUE 而不是回绕成负数）")
    void hugeDurationDoesNotOverflow() {
        // int 上限约 21.4 亿分钟 ≈ 4080 年，所以「1900 年」根本溢不出来（那是 6668 万分钟）。
        // 这里取公元前 5000 年，约 7026 年 ≈ 36.9 亿分钟，确保真的越界
        LocalDateTime ancient = LocalDateTime.of(-5000, 1, 1, 0, 0, 0);

        assertEquals(Integer.MAX_VALUE, TimeUtil.elapsedMinutes(ancient, T0));
    }

    @Test
    @DisplayName("下单时间缺失时记 0（不让详情页因一条脏数据 500）")
    void nullStartYieldsZero() {
        assertEquals(0, TimeUtil.elapsedMinutes(null, T0));
    }

    @Test
    @DisplayName("基准时刻缺失时记 0（对称防御，不抛 NPE）")
    void nullNowYieldsZero() {
        assertEquals(0, TimeUtil.elapsedMinutes(T0, null));
    }

    @Test
    @DisplayName("两个时间都缺失时记 0")
    void bothNullYieldZero() {
        assertEquals(0, TimeUtil.elapsedMinutes(null, null));
    }
}
