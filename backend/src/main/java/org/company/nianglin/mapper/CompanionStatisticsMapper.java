package org.company.nianglin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 陪诊员工作台的聚合只读查询（V3.3 · FR-Overview / FR-Trend / FR-Profile / FR-Income）。
 *
 * <h3>四条口径铁律（与 {@link StatisticsMapper} 同源）</h3>
 *
 * <ol>
 *   <li><b>{@code deleted = 0} 每条都要写</b>：原生 {@code @Select} 不经
 *       MyBatis-Plus 的 {@code @TableLogic} 注入，漏写就会把已删除数据算进统计。</li>
 *   <li><b>「已完成」= {@code COMPLETED} + {@code REVIEWED}</b>：把已评价的单排除
 *       会让完成率随着用户评价而下降。</li>
 *   <li><b>区间用 {@code [start, end]} 闭区间</b>，由调用方折算成当天 23:59:59。</li>
 *   <li><b>区间按 {@code visit_time} 过滤</b>（不是 {@code create_time}）：
 *       {@code elder.visit.order_count}（V3.2 家属端）数的是同一批「已完成订单」，
 *       两端必须用同一个时间字段，否则决策 6.2「同一指标只有一个口径」就破了。</li>
 * </ol>
 *
 * <h3>为什么按天聚合而不是按粒度聚合</h3>
 *
 * <p>与 {@link StatisticsMapper} 同理：DAY / WEEK / MONTH 三种粒度共用一条 SQL，
 * 聚合到目标粒度在 Java 侧完成（{@code StatisticsGranularity#bucketDate}）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.3
 */
@Mapper
public interface CompanionStatisticsMapper {

    /**
     * 区间内的订单聚合（概览用）。
     *
     * @return 一行形如
     *         {@code {completedCount: 28, cancelledCount: 2, serviceMinutes: 2520,
     *         income: 3680.00, orderAmount: 4000.00}}；无数据时各计数为 0
     */
    @Select("""
            SELECT COALESCE(SUM(CASE WHEN status IN ('COMPLETED', 'REVIEWED') THEN 1 ELSE 0 END), 0) AS completedCount,
                   COALESCE(SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelledCount,
                   COALESCE(SUM(CASE WHEN status IN ('COMPLETED', 'REVIEWED')
                                          AND start_time IS NOT NULL AND finish_time IS NOT NULL
                                    THEN TIMESTAMPDIFF(MINUTE, start_time, finish_time) ELSE 0 END), 0) AS serviceMinutes,
                   COALESCE(SUM(CASE WHEN status IN ('COMPLETED', 'REVIEWED')
                                    THEN COALESCE(actual_fee, fee) ELSE 0 END), 0) AS income,
                   COALESCE(SUM(CASE WHEN status IN ('COMPLETED', 'REVIEWED') THEN fee ELSE 0 END), 0) AS orderAmount
            FROM companion_order
            WHERE deleted = 0 AND companion_id = #{companionId}
              AND visit_time BETWEEN #{start} AND #{end}
            """)
    Map<String, Object> selectOrderStats(@Param("companionId") Long companionId,
                                         @Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    /**
     * 按天 + 状态统计订单数（ORDER 趋势的数据源）。
     *
     * @return 每行形如 {@code {d: 2026-09-15, status: COMPLETED, cnt: 13}}
     */
    @Select("""
            SELECT DATE_FORMAT(visit_time, '%Y-%m-%d') AS d, status, COUNT(*) AS cnt
            FROM companion_order
            WHERE deleted = 0 AND companion_id = #{companionId}
              AND visit_time BETWEEN #{start} AND #{end}
            GROUP BY d, status
            """)
    List<Map<String, Object>> selectDailyOrderCounts(@Param("companionId") Long companionId,
                                                      @Param("start") LocalDateTime start,
                                                      @Param("end") LocalDateTime end);

    /**
     * 按天统计收入（INCOME 趋势的数据源）。
     *
     * <p>只统计「已完成」的单；未结算时回退合同服务费 —— 与
     * {@code selectOrderStats} 的 {@code income} 同一个公式。</p>
     *
     * @return 每行形如 {@code {d: 2026-09-15, income: 680.00}}
     */
    @Select("""
            SELECT DATE_FORMAT(visit_time, '%Y-%m-%d') AS d,
                   COALESCE(SUM(COALESCE(actual_fee, fee)), 0) AS income
            FROM companion_order
            WHERE deleted = 0 AND companion_id = #{companionId}
              AND visit_time BETWEEN #{start} AND #{end}
              AND status IN ('COMPLETED', 'REVIEWED')
            GROUP BY d
            """)
    List<Map<String, Object>> selectDailyIncome(@Param("companionId") Long companionId,
                                                 @Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end);

    /**
     * 按天统计平均评分（RATING 趋势的数据源）。
     *
     * <p>只统计有效评价（{@code is_valid = 1}）；无评价的日期在 Java 侧补 0。</p>
     *
     * <p>同时返回当天的评价条数 {@code cnt}：日均值<b>不能</b>直接平均成周均值
     * （某周一天 5 条 5 星、另一天 1 条 1 星，日均值的平均是 3.0，
     * 而真实周均值是 4.33）。加权时需要条数，所以一并取出来。
     * 这里的 {@code AVG} <b>不四舍五入</b>，加权之后再统一取两位小数。</p>
     *
     * @return 每行形如 {@code {d: 2026-09-15, avgScore: 4.8, cnt: 2}}
     */
    @Select("""
            SELECT DATE_FORMAT(r.create_time, '%Y-%m-%d') AS d,
                   AVG(r.score) AS avgScore,
                   COUNT(*) AS cnt
            FROM order_review r
            WHERE r.deleted = 0 AND r.is_valid = 1 AND r.companion_id = #{companionId}
              AND r.create_time BETWEEN #{start} AND #{end}
            GROUP BY d
            """)
    List<Map<String, Object>> selectDailyRating(@Param("companionId") Long companionId,
                                                  @Param("start") LocalDateTime start,
                                                  @Param("end") LocalDateTime end);

    /**
     * 历史累计的订单聚合（画像用，<b>不受统计区间影响</b>）。
     *
     * @return 一行形如
     *         {@code {totalCompletedCount: 128, totalCancelledCount: 7,
     *         totalServiceMinutes: 8640, totalServedElderCount: 46, acceptedCount: 140}}
     */
    @Select("""
            SELECT COALESCE(SUM(CASE WHEN status IN ('COMPLETED', 'REVIEWED') THEN 1 ELSE 0 END), 0) AS totalCompletedCount,
                   COALESCE(SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS totalCancelledCount,
                   COALESCE(SUM(CASE WHEN status IN ('COMPLETED', 'REVIEWED')
                                          AND start_time IS NOT NULL AND finish_time IS NOT NULL
                                    THEN TIMESTAMPDIFF(MINUTE, start_time, finish_time) ELSE 0 END), 0) AS totalServiceMinutes,
                   COUNT(DISTINCT CASE WHEN status IN ('COMPLETED', 'REVIEWED') THEN elder_id END) AS totalServedElderCount,
                   COALESCE(SUM(CASE WHEN accept_time IS NOT NULL THEN 1 ELSE 0 END), 0) AS acceptedCount
            FROM companion_order
            WHERE deleted = 0 AND companion_id = #{companionId}
            """)
    Map<String, Object> selectLifetimeOrderStats(@Param("companionId") Long companionId);

    /**
     * 准时到院的订单数（准时率的分子）。
     *
     * <p>判定：存在 {@code ARRIVE} 打卡，且打卡时间 ≤ 订单 {@code visit_time}。
     * 「每单取最早一次 ARRIVE」在本表上等价于「存在一次」——
     * {@code uk_order_node (order_id, node)} 保证同一节点只打一次。</p>
     *
     * <p>分母（已接单订单数）在 {@link #selectLifetimeOrderStats} 的
     * {@code acceptedCount} 里。<b>没有 ARRIVE 打卡的订单视为不准时</b>：
     * 没打卡本身就是履约问题，不该因为「没记录」而被算成准时。</p>
     *
     * @return 准时到院订单数，无则 0
     */
    @Select("""
            SELECT COUNT(*)
            FROM companion_order o
            JOIN order_checkin c ON c.order_id = o.id
                                AND c.deleted = 0 AND c.node = 'ARRIVE'
            WHERE o.deleted = 0 AND o.companion_id = #{companionId}
              AND o.accept_time IS NOT NULL
              AND c.checkin_time <= o.visit_time
            """)
    Integer selectOnTimeArrivalCount(@Param("companionId") Long companionId);

    /**
     * 按科室统计已完成单数（擅长服务标签的数据源）。
     *
     * @return 每行形如 {@code {department: 普通门诊, cnt: 12}}；按单数降序
     */
    @Select("""
            SELECT department, COUNT(*) AS cnt
            FROM companion_order
            WHERE deleted = 0 AND companion_id = #{companionId}
              AND status IN ('COMPLETED', 'REVIEWED')
              AND department IS NOT NULL AND department <> ''
            GROUP BY department
            ORDER BY cnt DESC, department ASC
            """)
    List<Map<String, Object>> selectDepartmentStats(@Param("companionId") Long companionId);

    /**
     * 按结算状态分组的金额（收入中心的待结算 / 已结算）。
     *
     * @return 每行形如 {@code {paymentStatus: UNPAID, amount: 1200.00}}
     */
    @Select("""
            SELECT payment_status AS paymentStatus,
                   COALESCE(SUM(COALESCE(actual_fee, fee)), 0) AS amount
            FROM companion_order
            WHERE deleted = 0 AND companion_id = #{companionId}
              AND visit_time BETWEEN #{start} AND #{end}
              AND status IN ('COMPLETED', 'REVIEWED')
            GROUP BY payment_status
            """)
    List<Map<String, Object>> selectSettlementStats(@Param("companionId") Long companionId,
                                                     @Param("start") LocalDateTime start,
                                                     @Param("end") LocalDateTime end);

    /**
     * 费用项合计（收入中心的费用构成：代垫 vs 服务费）。
     *
     * @return 每行形如 {@code {itemType: ADVANCE, amount: 150.00}}
     */
    @Select("""
            SELECT f.item_type AS itemType, COALESCE(SUM(f.amount), 0) AS amount
            FROM order_fee_item f
            JOIN companion_order o ON o.id = f.order_id AND o.deleted = 0
            WHERE f.deleted = 0 AND o.companion_id = #{companionId}
              AND o.visit_time BETWEEN #{start} AND #{end}
              AND o.status IN ('COMPLETED', 'REVIEWED')
            GROUP BY f.item_type
            """)
    List<Map<String, Object>> selectFeeItemTotals(@Param("companionId") Long companionId,
                                                   @Param("start") LocalDateTime start,
                                                   @Param("end") LocalDateTime end);

    /**
     * 陪诊员档案里的平均评分（<b>不重算</b>，沿用评价聚合后的冗余字段）。
     *
     * @return 评分 0.00–5.00；没有档案时返回 {@code null}
     */
    @Select("SELECT score FROM companion_profile WHERE deleted = 0 AND user_id = #{companionId}")
    BigDecimal selectProfileScore(@Param("companionId") Long companionId);

    /**
     * 陪诊员档案里的累计完成单数（既有冗余口径，与评价聚合同一份数据）。
     *
     * @return 累计完成单数；没有档案时返回 {@code null}
     */
    @Select("SELECT order_count FROM companion_profile WHERE deleted = 0 AND user_id = #{companionId}")
    Integer selectProfileOrderCount(@Param("companionId") Long companionId);
}
