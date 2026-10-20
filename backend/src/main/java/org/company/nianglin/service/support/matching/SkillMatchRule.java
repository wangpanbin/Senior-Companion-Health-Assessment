package org.company.nianglin.service.support.matching;

/**
 * 服务能力匹配规则（V3.4 · FR-Rule）。
 *
 * <h3>口径</h3>
 *
 * <pre>
 * 该陪诊员在该科室的已完成单数 / 满量程  → 0.0 ~ 1.0（上限 1）
 * </pre>
 *
 * <p>「服务能力」不是平台给的头衔，而是<b>真实业务数据</b>：
 * 该陪诊员在目标订单科室上完成过多少单。这正是迭代计划 §33「擅长服务」
 * 的同一份数据 —— 一个陪诊员做过 12 单心内科，比他自称「擅长心内科」可信。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
public class SkillMatchRule implements MatchingRule {

    /** 归一化的满量程（单） */
    private final int maxOrders;

    public SkillMatchRule(int maxOrders) {
        this.maxOrders = maxOrders;
    }

    @Override
    public String dimension() {
        return MatchingWeights.DIMENSION_SKILL;
    }

    @Override
    public String displayName() {
        return "服务能力";
    }

    @Override
    public DimensionResult evaluate(MatchingCandidate candidate, MatchTarget target) {
        String department = target.department();
        int count = 0;
        if (department != null && candidate.departmentCounts() != null) {
            count = candidate.departmentCounts().getOrDefault(department, 0);
        }
        if (count <= 0) {
            return DimensionResult.of(0.0D, "未服务过该科室");
        }
        double score = Math.min(1.0D, (double) count / maxOrders);
        return DimensionResult.of(score, "服务过该科室 " + count + " 单");
    }
}
