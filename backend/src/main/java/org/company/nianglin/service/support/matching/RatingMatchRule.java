package org.company.nianglin.service.support.matching;

import java.math.BigDecimal;

/**
 * 评分匹配规则（V3.4 · FR-Rule）。
 *
 * <h3>口径</h3>
 *
 * <pre>
 * 陪诊员平均评分 / 5 → 0.0 ~ 1.0
 * </pre>
 *
 * <p>评分读的是 {@code companion_profile.score}（评价聚合后的冗余字段，
 * 由评价提交时在事务内刷新）—— <b>不在这里重算</b>，
 * 避免每次匹配都去扫一遍评价表。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
public class RatingMatchRule implements MatchingRule {

    /** 评分满分 */
    private static final BigDecimal FULL_SCORE = new BigDecimal("5.00");

    @Override
    public String dimension() {
        return MatchingWeights.DIMENSION_RATING;
    }

    @Override
    public String displayName() {
        return "评分";
    }

    @Override
    public DimensionResult evaluate(MatchingCandidate candidate, MatchTarget target) {
        BigDecimal rating = candidate.rating();
        if (rating == null) {
            return DimensionResult.degraded(0.0D, "暂无评分", "陪诊员暂无评分，评分维度记 0");
        }
        if (rating.signum() <= 0) {
            return DimensionResult.degraded(0.0D, "暂无评分", "陪诊员暂无有效评分，评分维度记 0");
        }
        double score = Math.min(1.0D, rating.doubleValue() / FULL_SCORE.doubleValue());
        return DimensionResult.of(score, "评分 " + rating.stripTrailingZeros().toPlainString());
    }
}
