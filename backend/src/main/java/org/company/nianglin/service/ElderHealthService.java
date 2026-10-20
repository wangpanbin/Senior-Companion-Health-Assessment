package org.company.nianglin.service;

import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.dto.HealthTimelineQuery;
import org.company.nianglin.vo.ElderHealthOverviewVO;
import org.company.nianglin.vo.FamilyHealthSummaryVO;
import org.company.nianglin.vo.HealthTimelineVO;

import java.util.List;

/**
 * 老人健康协同中心（V3.2 · FR-Overview / FR-Timeline / FR-Summary）。
 *
 * <h3>本接口的三条硬约束</h3>
 *
 * <ol>
 *   <li><b>只读</b>：本版零写操作，不改订单状态机、不改用药规则、不新建任何表。</li>
 *   <li><b>事实优先</b>：只陈述「几次、什么时候、谁做了」，不输出任何健康判断
 *       （合规红线-1：不做诊断、不开药方）。</li>
 *   <li><b>家属端与老人端共用一份聚合</b>：老人端拿的是同一份结果的子集，
 *       而不是另算一套 —— 两套口径迟早漂移（roadmap 决策 2.6 / 6.2）。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
public interface ElderHealthService {

    /**
     * 家庭健康协同首页摘要：当前家属名下每位老人的轻量摘要。
     *
     * <p>没有绑定老人时返回空列表，不报错、不返回 {@code null}。</p>
     *
     * @return 摘要列表，按最近活动倒序、老人 ID 升序
     */
    List<FamilyHealthSummaryVO> familySummary();

    /**
     * 某位老人的健康概览（家属端）。
     *
     * @param elderId 老人档案 ID
     * @param query   区间入参
     * @return 概览
     */
    ElderHealthOverviewVO overview(Long elderId, DateRangeQuery query);

    /**
     * 老人本人的健康概览。
     *
     * <p>返回的是家属端概览的<b>子集</b>：不含费用、催单、履约风险与家属姓名。</p>
     *
     * @param query 区间入参
     * @return 概览
     */
    ElderHealthOverviewVO selfOverview(DateRangeQuery query);

    /**
     * 某位老人的健康时间线（家属端）。
     *
     * @param elderId 老人档案 ID
     * @param query   区间 / 分类 / 分页入参
     * @return 时间线
     */
    HealthTimelineVO timeline(Long elderId, HealthTimelineQuery query);

    /**
     * 老人本人的健康时间线。
     *
     * <p>不接收任何 {@code elderId} 入参 —— 老人只能看自己，
     * 这条接口不接受路径变量，从根上杜绝越权面。</p>
     *
     * @param query 区间 / 分类 / 分页入参
     * @return 时间线
     */
    HealthTimelineVO selfTimeline(HealthTimelineQuery query);
}
