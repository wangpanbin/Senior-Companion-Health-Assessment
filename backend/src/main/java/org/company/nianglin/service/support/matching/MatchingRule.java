package org.company.nianglin.service.support.matching;

/**
 * 匹配规则（V3.4 · FR-Rule）。
 *
 * <h3>规则是纯函数</h3>
 *
 * <p>接口刻意不带 Spring 注解：规则只吃快照、只吐结果，
 * 不碰数据库、不碰时钟、不碰配置中心。这样「每个阈值的边界」都能被
 * 毫秒级地反复测试 —— 而这正是匹配引擎最需要被锁住的东西。</p>
 *
 * <p>配置（满量程等）由构造参数显式传入，而不是规则自己去注入 ——
 * 这样测试里能直接构造出「满量程 = 10 米」这样的边界值。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
public interface MatchingRule {

    /** 维度名（与配置键一一对应：{@code region} / {@code time} / ...） */
    String dimension();

    /** 中文名（用于解释文案） */
    String displayName();

    /**
     * 评估某个候选人对某个订单的该维度得分。
     *
     * @param candidate 候选人快照
     * @param target    订单快照
     * @return 维度得分 + 理由 + 降级说明
     */
    DimensionResult evaluate(MatchingCandidate candidate, MatchTarget target);
}
