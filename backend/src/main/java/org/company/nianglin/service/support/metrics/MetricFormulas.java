package org.company.nianglin.service.support.metrics;

import org.company.nianglin.vo.ChartDataVO;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 运营指标的<b>唯一公式实现</b>（V3.6 · T3 · ADR-0017 决策 2）。
 *
 * <h3>它解决什么</h3>
 *
 * <p>ADR-0017 决策 2 的要求是「物理上只有一份实现」—— 因为
 * 「同一个指标在驾驶舱与效果对比里必须是同一个数」这件事，
 * 靠的是<b>同一段代码</b>，不是靠两处各写一遍再祈祷它们一样。</p>
 *
 * <p>本类之前，履约率 / 投诉率 / 平均评分 / 完成率（按时）/ 平均接单时间
 * 这五个公式各有两份：一份在 {@code OperationsServiceImpl}（驾驶舱概览与健康度），
 * 一份在 {@code service.support.metrics} 的计算器族（效果对比）。
 * 两份只要有一处改了公式，两个端点就会给出两个数 ——
 * 那正是 V3.2 在用药三桶上踩过的坑（ADR-0014）。</p>
 *
 * <h3>为什么是纯静态函数，而不是让 Service 去调注册表</h3>
 *
 * <p>{@link MetricCalculator#calculate} 的契约是「按区间自己取数」
 * （{@code calculate(DateRange)}）。若驾驶舱改成走注册表，
 * 每个指标都要<b>各自再打一次库</b>（概览会从 1 次聚合变成 4~5 次），
 * 用一次性能换来的只是「换个地方调同一个公式」。</p>
 *
 * <p>所以这里取中间路线：<b>公式层</b>单一实现（两处共用同一段代码），
 * <b>取数层</b>仍是各取各的 —— 但取的都是同一个
 * {@code OperationsMapper#selectOrderStats} 聚合，SQL 层面已经同源。</p>
 *
 * <h3>不变量</h3>
 *
 * <ul>
 *   <li>纯函数：不碰数据库、不碰时钟、不碰配置；</li>
 *   <li>空数据返回 {@code "0"} / {@code "0.00%"} / {@code "0.00"}，
 *       <b>不返回 {@code null}、不抛异常</b>；</li>
 *   <li>公式与指标字典（{@code metrics/metric-dictionary.yml}）逐字同源。</li>
 * </ul>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
public final class MetricFormulas {

    /* ================================================================== */
    /* 领域公式（ADR-0017 决策 2：物理上只有一份）                        */
    /* ================================================================== */

    /**
     * 履约率 = (COMPLETED + REVIEWED) ÷ (COMPLETED + REVIEWED + CANCELLED)。
     *
     * <p>指标 key {@code operations.fulfillment_rate}，
     * 别名 {@code companion.workbench.completion_rate}（公式逐字相同，只是出口不同）。</p>
     *
     * @param orderStats 订单聚合行（{@code OperationsMapper#selectOrderStats}）
     * @return 形如 {@code "96.40%"}；无完成单时 {@code "0.00%"}
     */
    public static String fulfillmentRate(Map<String, Object> orderStats) {
        long completed = longOf(orderStats, "completedCount");
        long cancelled = longOf(orderStats, "cancelledCount");
        return percent(completed, completed + cancelled);
    }

    /**
     * 完成率（按时）= 按时完成单数 ÷ 已完成单数。
     *
     * <p>指标 key {@code operations.completion_rate}。
     * ⚠️ 它与履约率<b>不是同一个指标</b>：履约率的分母含取消单，
     * 完成率（按时）的分母只有已完成单。</p>
     *
     * @param orderStats 订单聚合行
     * @return 形如 {@code "87.61%"}；无已完成单时 {@code "0.00%"}
     */
    public static String completionRate(Map<String, Object> orderStats) {
        long completed = longOf(orderStats, "completedCount");
        long onTime = longOf(orderStats, "onTimeCompletedCount");
        return percent(onTime, completed);
    }

    /**
     * 投诉率 = 投诉<b>订单</b>数 ÷ 区间订单数。
     *
     * <p>指标 key {@code operations.complaint_rate}。分子是去重订单数
     * （同一单多次投诉只算一单）。</p>
     *
     * @param orderStats    订单聚合行
     * @param complaintStats 投诉聚合行
     * @return 形如 {@code "1.20%"}；区间无订单时 {@code "0.00%"}
     */
    public static String complaintRate(Map<String, Object> orderStats,
                                        Map<String, Object> complaintStats) {
        long total = longOf(orderStats, "totalCount");
        long complaintOrders = longOf(complaintStats, "complaintOrderCount");
        return percent(complaintOrders, total);
    }

    /**
     * 平均评分 = 区间内<b>有效评价</b>的平均分，两位小数、不带 {@code %}。
     *
     * <p>指标 key {@code operations.average_score}。无有效评价时 {@code "0.00"}。</p>
     *
     * @param reviewStats 评价聚合行
     * @return 形如 {@code "4.82"}
     */
    public static String averageScore(Map<String, Object> reviewStats) {
        return fixed(reviewStats == null ? null : reviewStats.get("averageScore"));
    }

    /**
     * 平均接单时间（分钟）：已接单订单从下单到接单的耗时均值。
     *
     * <p>指标 key {@code operations.avg_accept_minutes}。没有已接单订单时记 {@code 0}。</p>
     *
     * @param orderStats 订单聚合行
     * @return 分钟数
     */
    public static long avgAcceptMinutes(Map<String, Object> orderStats) {
        return rounded(orderStats == null ? null : orderStats.get("avgAcceptMinutes"));
    }

    /* ================================================================== */
    /* 取值与渲染                                                          */
    /* ================================================================== */

    /** 从聚合行取整数值，{@code null} 记 0 */
    public static long longOf(Map<String, Object> row, String key) {
        return longOf(row == null ? null : row.get(key));
    }

    /** 把任意查询结果转成长整数，{@code null} / 非法值记 0 */
    public static long longOf(Object value) {
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

    /** 把任意查询结果转成小数，{@code null} 记 0 */
    public static BigDecimal decimalOf(Object value) {
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
    public static String percent(long numerator, long denominator) {
        return ChartDataVO.percent(numerator, denominator);
    }

    /** 计数字符串（不带小数位） */
    public static String countText(long value) {
        return String.valueOf(value);
    }

    /** 两位小数字符串（不带 {@code %}），用于评分、时长这类非比率指标 */
    public static String fixed(Object value) {
        return decimalOf(value).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 四舍五入到整数（用于「平均接单时间」这类分钟数） */
    public static long rounded(Object value) {
        return decimalOf(value).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private MetricFormulas() {
    }
}
