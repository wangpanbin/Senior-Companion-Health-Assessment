package org.company.nianglin.service.support.metrics;

import lombok.RequiredArgsConstructor;
import org.company.nianglin.common.DateRange;
import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.mapper.OperationsMapper;
import org.company.nianglin.vo.ChartDataVO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;

/**
 * 指标计算器的公共基类（V3.6 · T3）。
 *
 * <h3>它承担什么</h3>
 *
 * <p>各指标计算器只关心「这一个指标怎么算」，共用的取数与格式化收在这里：
 * 区间 → 时刻的折算、聚合行的取值、空值兜底、百分比与两位小数的渲染。</p>
 *
 * <h3>为什么取数走 {@link OperationsMapper} 而不是各写各的 SQL</h3>
 *
 * <p>驾驶舱（V3.5）已经在算同一批聚合了。效果对比如果另起一套 SQL，
 * 同一个指标在两个端点上就会因为两段 SQL 的口径差异给出两个数 ——
 * 而这正是 V3.6 要消灭的那类问题。共用聚合是「只有一个口径」最省事的保证方式。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@RequiredArgsConstructor
public abstract class AbstractMetricCalculator implements MetricCalculator {

    /** 运营驾驶舱聚合（与 V3.5 驾驶舱同源） */
    protected final OperationsMapper operationsMapper;

    /** 运营驾驶舱配置（「按时完成」窗口等阈值） */
    protected final OperationsProperties operationsProperties;

    /**
     * 区间内的订单聚合。
     *
     * @param range 统计区间
     * @return 聚合行（{@link OperationsMapper#selectOrderStats}）
     */
    protected Map<String, Object> orderStats(DateRange range) {
        LocalDate today = LocalDate.now();
        return operationsMapper.selectOrderStats(
                range.startTime(), range.endTime(),
                operationsProperties.getFulfillmentWindowMinutes(),
                today.atStartOfDay(),
                // 用「次日零点」而不是「今日 23:59:59」：DATETIME(3) 精度下 23:59:59.500 会漏掉
                today.plusDays(1).atStartOfDay());
    }

    /**
     * 区间内的投诉聚合。
     *
     * @param range 统计区间
     * @return 聚合行
     */
    protected Map<String, Object> complaintStats(DateRange range) {
        return operationsMapper.selectComplaintStats(range.startTime(), range.endTime());
    }

    /**
     * 区间内的评价聚合。
     *
     * @param range 统计区间
     * @return 聚合行
     */
    protected Map<String, Object> reviewStats(DateRange range) {
        return operationsMapper.selectReviewStats(range.startTime(), range.endTime());
    }

    /**
     * 区间内的用药任务三桶聚合。
     *
     * @param range 统计区间
     * @return 聚合行
     */
    protected Map<String, Object> medicationStats(DateRange range) {
        return operationsMapper.selectMedicationAggregates(range.startTime(), range.endTime());
    }

    /* ================================================================== */
    /* 取值与渲染                                                          */
    /* ================================================================== */

    /** 从聚合行取整数值，{@code null} 记 0 */
    protected static long longOf(Map<String, Object> row, String key) {
        return longOf(row == null ? null : row.get(key));
    }

    /** 把任意查询结果转成长整数，{@code null} / 非法值记 0 */
    protected static long longOf(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 从聚合行取小数值，{@code null} 记 0 */
    protected static BigDecimal decimalOf(Map<String, Object> row, String key) {
        return decimalOf(row == null ? null : row.get(key));
    }

    /** 把任意查询结果转成小数，{@code null} 记 0 */
    protected static BigDecimal decimalOf(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** 百分比字符串（两位小数 + {@code %}）；分母为 0 时 {@code "0.00%"} */
    protected static String percent(long numerator, long denominator) {
        return ChartDataVO.percent(numerator, denominator);
    }

    /** 计数字符串（不带小数位） */
    protected static String count(long value) {
        return String.valueOf(value);
    }

    /** 两位小数字符串（不带 {@code %}），用于评分、时长这类非比率指标 */
    protected static String fixed(Object value) {
        return decimalOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 四舍五入到整数（用于「平均接单时间」这类分钟数） */
    protected static long rounded(Object value) {
        return decimalOf(value).setScale(0, RoundingMode.HALF_UP).longValue();
    }
}
