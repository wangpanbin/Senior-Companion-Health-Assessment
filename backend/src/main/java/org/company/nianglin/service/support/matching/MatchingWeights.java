package org.company.nianglin.service.support.matching;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 匹配权重（V3.4 · FR-Config）。
 *
 * <h3>它是「配置」与「纯函数」之间的桥</h3>
 *
 * <p>{@link MatchingProperties} 是 Spring 的配置绑定对象，而规则与加权计算
 * 必须能在没有 Spring 上下文的情况下被测试。所以这里把五个权重抽成一个
 * 不可变值对象，由 Service 层从配置构造出来，再喂给纯函数。</p>
 *
 * <p>「权重之和 = 1」由 {@code MatchingProperties} 在<b>启动期</b>校验 ——
 * 这里再校验一次是为了让纯函数在测试里被单独使用时也守得住底线。</p>
 *
 * @param region  区域匹配权重
 * @param time    时间匹配权重
 * @param distance 距离权重
 * @param skill   服务能力权重
 * @param rating  评分权重
 * @author 银龄伴诊团队
 * @since V3.4
 */
public record MatchingWeights(double region, double time, double distance,
                              double skill, double rating) {

    /** 维度名常量（与 {@link MatchingRule#dimension()} 对应） */
    public static final String DIMENSION_REGION = "region";
    public static final String DIMENSION_TIME = "time";
    public static final String DIMENSION_DISTANCE = "distance";
    public static final String DIMENSION_SKILL = "skill";
    public static final String DIMENSION_RATING = "rating";

    /** 权重之和的容差（浮点累加不可能精确等于 1） */
    private static final double SUM_TOLERANCE = 1e-6D;

    /** 五个维度名，顺序即打分顺序（保证解释文案顺序稳定） */
    public static final java.util.List<String> DIMENSIONS = java.util.List.of(
            DIMENSION_REGION, DIMENSION_TIME, DIMENSION_DISTANCE, DIMENSION_SKILL, DIMENSION_RATING);

    /**
     * 取某个维度的权重。
     *
     * @param dimension 维度名
     * @return 权重；**未知维度返回 0**（而不是抛异常）——
     *         多一条规则不会让整个匹配崩掉，只会是不参与加权
     */
    public double weightOf(String dimension) {
        return switch (dimension) {
            case DIMENSION_REGION -> region;
            case DIMENSION_TIME -> time;
            case DIMENSION_DISTANCE -> distance;
            case DIMENSION_SKILL -> skill;
            case DIMENSION_RATING -> rating;
            default -> 0D;
        };
    }

    /** 权重之和 */
    public double sum() {
        return region + time + distance + skill + rating;
    }

    /**
     * 权重之和是否等于 1。
     *
     * @return true 表示权重配置合法
     */
    public boolean isNormalized() {
        return Math.abs(sum() - 1.0D) <= SUM_TOLERANCE;
    }

    /** 权重表（按维度名索引），用于调试与日志 */
    public Map<String, Double> asMap() {
        Map<String, Double> map = new LinkedHashMap<>();
        for (String dimension : DIMENSIONS) {
            map.put(dimension, weightOf(dimension));
        }
        return map;
    }
}
