package org.company.nianglin.service;

import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.service.support.matching.OrderMatchRank;
import org.company.nianglin.vo.OrderRecommendVO;

import java.util.List;

/**
 * 智能调度服务（V3.4 · FR-Service）。
 *
 * <h3>两个方向，同一套打分</h3>
 *
 * <p>{@link #recommendCompanions} 是「订单 → 找陪诊员」（家属端看候选）；
 * {@link #scoreOrders} 是「陪诊员 → 挑订单」（大厅排序）。
 * 两者用的是<b>同一批规则与同一份权重</b>，
 * 否则会出现「家属看到推荐 A，大厅里 A 却排在后面」这种自相矛盾。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
public interface MatchingService {

    /**
     * 为订单推荐候选陪诊员（家属端）。
     *
     * <p>过滤链：工作状态 → 资质 → 区域 → 时段 → 档期冲突，顺序固定、逐级短路。
     * 每一步的淘汰原因都会被记录，便于排查「为什么没有推荐」。</p>
     *
     * @param order 被匹配的订单（非 null）
     * @param limit 返回条数上限（调用方负责夹到合法范围）
     * @return 候选结果（含通过过滤链的候选总数与 Top N）
     */
    OrderRecommendVO recommendCompanions(CompanionOrder order, int limit);

    /**
     * 给一批订单按「当前陪诊员」的匹配度打分（大厅排序用）。
     *
     * @param profile 当前陪诊员档案（非 null）
     * @param orders  候选订单（非 null）
     * @return 每个订单一条排名记录
     */
    List<OrderMatchRank> scoreOrders(CompanionProfile profile, List<CompanionOrder> orders);
}
