package org.company.nianglin.service.support.matching;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 匹配得分（V3.4 · FR-Rule）。
 *
 * <h3>「可解释」是这个类的全部意义</h3>
 *
 * <p>如果只返回一个 {@code double}，那就回到了「黑盒推荐」——
 * 家属看到「匹配度 86」不知道凭什么，陪诊员看到「凭什么是我」也不知道，
 * 运营调权重时更无从判断哪一维在起作用。</p>
 *
 * <p>所以得分必须自带三样东西：<b>各维度得分</b>、<b>命中理由</b>、<b>降级说明</b>。
 * 每一维都得能说出「为什么是这个分」。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
public class MatchingScore {

    /** 各维度得分（0.0 ~ 1.0），按维度顺序保持稳定 */
    private final Map<String, DimensionResult> dimensions = new LinkedHashMap<>();

    /** 加权总分（0.0 ~ 1.0） */
    private final double total;

    /**
     * 构造得分。
     *
     * @param dimensions 各维度评估结果
     * @param total      加权总分
     */
    public MatchingScore(Map<String, DimensionResult> dimensions, double total) {
        if (dimensions != null) {
            this.dimensions.putAll(dimensions);
        }
        this.total = total;
    }

    /**
     * 按权重加权求总分（**纯函数**，无 Spring 上下文）。
     *
     * <p>权重之和不为 1 时，总分会超出 {@code [0,1]} 或被整体压低。
     * 这里<b>不做静默归一化</b> —— 配置错了应该在启动期就失败
     * （见 {@code MatchingProperties#validate}），而不是在这里偷偷修正。</p>
     *
     * @param dimensions 各维度评估结果
     * @param weights   权重
     * @return 加权总分
     */
    public static double weightedTotal(Map<String, DimensionResult> dimensions, MatchingWeights weights) {
        if (dimensions == null || dimensions.isEmpty()) {
            return 0D;
        }
        double sum = 0D;
        for (Map.Entry<String, DimensionResult> entry : dimensions.entrySet()) {
            DimensionResult result = entry.getValue();
            if (result == null) {
                continue;
            }
            sum += result.score() * weights.weightOf(entry.getKey());
        }
        return sum;
    }

    /** 加权总分（0.0 ~ 1.0） */
    public double total() {
        return total;
    }

    /** 加权总分（0–100 的整数，给前端展示用） */
    public int totalPercent() {
        return (int) Math.round(total * 100D);
    }

    /** 某个维度的得分（0.0 ~ 1.0）；维度不存在返回 0 */
    public double dimensionScore(String dimension) {
        DimensionResult result = dimensions.get(dimension);
        return result == null ? 0D : result.score();
    }

    /** 某个维度的得分（0–100 的整数）；维度不存在返回 0 */
    public int dimensionPercent(String dimension) {
        return (int) Math.round(dimensionScore(dimension) * 100D);
    }

    /** 某维度的降级说明；没有降级返回 {@code null} */
    public String dimensionNote(String dimension) {
        DimensionResult result = dimensions.get(dimension);
        return result == null ? null : result.note();
    }

    /** 全部维度结果 */
    public Map<String, DimensionResult> dimensions() {
        return Map.copyOf(dimensions);
    }

    /**
     * 命中理由（面向人的一句话们）。
     *
     * <p>只收**没有降级**的维度的理由 —— 降级维度的事实写在
     * {@link #notes()} 里，混在一起会让人误以为「距离不匹配」。</p>
     */
    public List<String> reasons() {
        List<String> reasons = new ArrayList<>();
        for (String dimension : MatchingWeights.DIMENSIONS) {
            DimensionResult result = dimensions.get(dimension);
            if (result == null || result.isDegraded() || result.reason() == null) {
                continue;
            }
            reasons.add(result.reason());
        }
        return reasons;
    }

    /**
     * 降级说明（因数据缺失而没算出来的维度）。
     *
     * <p>这些维度的分是 0，但 0 的含义是「没法算」而不是「不匹配」——
     * 这两件事必须分开说，否则运营会拿一个不存在的结论去调权重。</p>
     */
    public List<String> notes() {
        List<String> notes = new ArrayList<>();
        for (String dimension : MatchingWeights.DIMENSIONS) {
            DimensionResult result = dimensions.get(dimension);
            if (result == null || !result.isDegraded()) {
                continue;
            }
            notes.add(result.note());
        }
        return notes;
    }
}
