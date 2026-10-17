package org.company.nianglin.util;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 时间计算工具（V3.1 · T4）。
 *
 * <p>目前只有一个方法，但它存在的理由是<b>消除重复</b>而不是功能：
 * 「已等待分钟数」的钳制规则（下单时间缺失记 0、时钟漂移不返回负数、溢出不炸 int）
 * 需要在订单出参装配与履约详情装配两处同时成立。两处各写一遍，迟早会出现
 * 「订单详情显示 45 分钟、履约详情显示 -3 分钟」这种只有一线同事才看得出来口径分裂。</p>
 *
 * <p>{@link #elapsedMinutes} 的两个时间都是<b>参数</b>，与
 * {@code FulfillmentRiskEvaluator} 的无时钟约定一致：测试给定两个 {@code now}
 * 就能验证「等待时长随时间推进而自增」，不必 sleep、不必 mock 时钟。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
public final class TimeUtil {

    private TimeUtil() {
        // 工具类，禁止实例化
    }

    /**
     * 从 {@code start} 到 {@code now} 已过去的整分钟数。
     *
     * @param start 起始时间；{@code null} 视为 0
     * @param now   基准时刻；{@code null} 视为 0
     * @return 已过去的分钟数，钳制在 {@code [0, Integer.MAX_VALUE]}；
     *         {@code start} 晚于 {@code now}（时钟漂移 / 人工改库）时返回 0
     */
    public static int elapsedMinutes(LocalDateTime start, LocalDateTime now) {
        if (start == null || now == null) {
            return 0;
        }
        long minutes = Duration.between(start, now).toMinutes();
        return (int) Math.max(0L, Math.min(minutes, Integer.MAX_VALUE));
    }
}
