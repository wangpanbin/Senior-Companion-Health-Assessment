package org.company.nianglin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.company.nianglin.constant.MedicationBucketSql;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 平台运营驾驶舱的聚合只读查询（V3.5 · T2）。
 *
 * <h3>四条口径铁律（与 {@code StatisticsMapper} / {@code CompanionStatisticsMapper} 同源）</h3>
 *
 * <ol>
 *   <li><b>{@code deleted = 0} 每条都要写</b>：原生 {@code @Select} 不经
 *       MyBatis-Plus 的 {@code @TableLogic} 注入，漏写就会把已删除数据算进统计。</li>
 *   <li><b>「已完成」= {@code COMPLETED} + {@code REVIEWED}</b>：把已评价的单排除
 *       会让完成率随着用户评价而下降。</li>
 *   <li><b>区间用 {@code [start, end]} 闭区间</b>，由调用方折算成当天 23:59:59。</li>
 *   <li><b>订单按 {@code create_time} 过滤</b>（不是 {@code visit_time}）：
 *       驾驶舱看的是「平台在这段时间里接了多少单」，与 M10 统计模块
 *       （{@code StatisticsMapper#selectOrderCountsByStatus}）保持同一时间字段；
 *       概览与 ORDER / FULFILLMENT_RATE 两条趋势也必须同源，
 *       否则同一个指标在两个端点上会给出两个数。</li>
 * </ol>
 *
 * <h3>用药三桶只出现一次</h3>
 *
 * <p>漏服判定复用 {@link MedicationBucketSql#CASE_BUCKET}（ADR-0014），
 * 驾驶舱不另写一套 —— 那正是 V3.2 踩过的坑：两处各写各的 {@code CASE WHEN}，
 * 最后谁也说不清「漏服」到底指什么。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.5
 */
@Mapper
public interface OperationsMapper {

    /**
     * 区间内的订单聚合（概览 / 健康度 / 趋势 / V3.6 指标计算器族的数据源）。
     *
     * <p>⚠️ V3.6 起，本聚合同时供<b>指标计算器</b>使用（{@code service.support.metrics}），
     * 因此「同一个指标在驾驶舱与效果对比里必须是同一个数」这件事，
     * 由「两边都从这里取数」保证，而不是靠两边各写一遍公式。</p>
     *
     * @param start                    区间起点（含）
     * @param end                      区间终点（含）
     * @param fulfillmentWindowMinutes 「按时完成」的判定窗口（分钟，配置项）
     * @param todayStart               今日起点（应用侧按本地时区算，不依赖数据库时区）
     * @param todayEnd                 今日终点（不含）
     * @return 一行形如
     *         {@code {todayCount: 128, totalCount: 240, completedCount: 113,
     *         cancelledCount: 4, inServiceCount: 9, pendingCount: 6,
     *         onTimeCompletedCount: 99, avgAcceptMinutes: 6, avgFinishDelayMinutes: 38,
     *         manualRequiredCount: 5, avgServiceDurationMinutes: 86.50}}；
     *         无数据时各计数为 0，均值为 {@code null}
     */
    @Select("""
            SELECT COALESCE(SUM(CASE WHEN create_time >= #{todayStart} AND create_time < #{todayEnd}
                                      THEN 1 ELSE 0 END), 0) AS todayCount,
                   COUNT(*) AS totalCount,
                   COALESCE(SUM(CASE WHEN status IN ('COMPLETED', 'REVIEWED') THEN 1 ELSE 0 END), 0) AS completedCount,
                   COALESCE(SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelledCount,
                   COALESCE(SUM(CASE WHEN status = 'IN_SERVICE' THEN 1 ELSE 0 END), 0) AS inServiceCount,
                   COALESCE(SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END), 0) AS pendingCount,
                   COALESCE(SUM(CASE WHEN status IN ('COMPLETED', 'REVIEWED')
                                     AND finish_time IS NOT NULL AND visit_time IS NOT NULL
                                     AND finish_time <= DATE_ADD(visit_time, INTERVAL #{fulfillmentWindowMinutes} MINUTE)
                                THEN 1 ELSE 0 END), 0) AS onTimeCompletedCount,
                   ROUND(AVG(CASE WHEN accept_time IS NOT NULL
                                THEN TIMESTAMPDIFF(MINUTE, create_time, accept_time) END)) AS avgAcceptMinutes,
                   ROUND(AVG(CASE WHEN finish_time IS NOT NULL AND visit_time IS NOT NULL
                                THEN TIMESTAMPDIFF(MINUTE, visit_time, finish_time) END)) AS avgFinishDelayMinutes,
                   COALESCE(SUM(CASE WHEN fulfillment_risk = 'MANUAL_REQUIRED' THEN 1 ELSE 0 END), 0) AS manualRequiredCount,
                   ROUND(AVG(CASE WHEN status IN ('COMPLETED', 'REVIEWED') AND start_time IS NOT NULL
                                    AND finish_time IS NOT NULL
                                THEN TIMESTAMPDIFF(MINUTE, start_time, finish_time) END), 2) AS avgServiceDurationMinutes
            FROM companion_order
            WHERE deleted = 0 AND create_time BETWEEN #{start} AND #{end}
            """)
    Map<String, Object> selectOrderStats(@Param("start") LocalDateTime start,
                                          @Param("end") LocalDateTime end,
                                          @Param("fulfillmentWindowMinutes") int fulfillmentWindowMinutes,
                                          @Param("todayStart") LocalDateTime todayStart,
                                          @Param("todayEnd") LocalDateTime todayEnd);

    /**
     * 区间内的陪诊员聚合（健康度用）。
     *
     * <p>{@code activeCompanionCount} = 区间内<b>接过单</b>的陪诊员数（去重）；
     * 「人均订单 / 人均收入」由同一次聚合派生，不额外往返数据库。</p>
     *
     * @return 一行形如
     *         {@code {activeCompanionCount: 24, totalOrderCount: 240,
     *         totalIncome: 36800.00, avgOrdersPerCompanion: 10.00,
     *         avgIncomePerCompanion: 1533.33}}
     */
    @Select("""
            SELECT COUNT(DISTINCT companion_id) AS activeCompanionCount,
                   COUNT(*) AS totalOrderCount,
                   COALESCE(SUM(CASE WHEN status IN ('COMPLETED', 'REVIEWED')
                                     THEN COALESCE(actual_fee, fee) ELSE 0 END), 0) AS totalIncome,
                   ROUND(COUNT(*) / NULLIF(COUNT(DISTINCT companion_id), 0), 2) AS avgOrdersPerCompanion,
                   ROUND(COALESCE(SUM(CASE WHEN status IN ('COMPLETED', 'REVIEWED')
                                          THEN COALESCE(actual_fee, fee) ELSE 0 END), 0)
                         / NULLIF(COUNT(DISTINCT companion_id), 0), 2) AS avgIncomePerCompanion
            FROM companion_order
            WHERE deleted = 0 AND companion_id IS NOT NULL
              AND create_time BETWEEN #{start} AND #{end}
            """)
    Map<String, Object> selectCompanionStats(@Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);

    /**
     * 区间内的投诉聚合（概览 / 异常中心用）。
     *
     * <p>「投诉率」的分子是<b>投诉订单数</b>（{@code COUNT(DISTINCT order_id)}）：
     * 同一笔订单被投诉两次只算一单，否则平台可以靠「重复投诉同一笔单」把投诉率刷上去，
     * 指标就失去了度量服务质量的含义。</p>
     *
     * @return 一行形如 {@code {complaintOrderCount: 32, complaintCount: 32,
     *         involvedCompanionCount: 21}}
     */
    @Select("""
            SELECT COUNT(DISTINCT order_id) AS complaintOrderCount,
                   COUNT(*) AS complaintCount,
                   COUNT(DISTINCT CASE WHEN target_role = 'COMPANION' THEN target_user_id END) AS involvedCompanionCount
            FROM complaint
            WHERE deleted = 0 AND create_time BETWEEN #{start} AND #{end}
            """)
    Map<String, Object> selectComplaintStats(@Param("start") LocalDateTime start,
                                              @Param("end") LocalDateTime end);

    /**
     * 区间内的评价聚合（概览 / 健康度的平均评分）。
     *
     * <p>只统计有效评价（{@code is_valid = 1}）：管理员判定无效的评价不参与聚合，
     * 否则管理员「洗白」一条差评反而会把平均分拉高。</p>
     *
     * @return 一行形如 {@code {averageScore: 4.10, reviewCount: 30}}
     */
    @Select("""
            SELECT ROUND(AVG(score), 2) AS averageScore,
                   COUNT(*) AS reviewCount
            FROM order_review
            WHERE deleted = 0 AND is_valid = 1 AND create_time BETWEEN #{start} AND #{end}
            """)
    Map<String, Object> selectReviewStats(@Param("start") LocalDateTime start,
                                           @Param("end") LocalDateTime end);

    /**
     * 按天分组的订单聚合（ORDER / COMPANION_ACTIVE / FULFILLMENT_RATE 三条趋势共用）。
     *
     * <p>一行给齐三��趋势要用的数，避免为了三个维度跑三次全表扫描。</p>
     *
     * @return 每行形如 {@code {d: 2026-09-15, totalCount: 13, completedCount: 9,
     *         cancelledCount: 1, activeCompanionCount: 4}}
     */
    @Select("""
            SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS d,
                   COUNT(*) AS totalCount,
                   COALESCE(SUM(CASE WHEN status IN ('COMPLETED', 'REVIEWED') THEN 1 ELSE 0 END), 0) AS completedCount,
                   COALESCE(SUM(CASE WHEN status = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS cancelledCount,
                   COUNT(DISTINCT companion_id) AS activeCompanionCount
            FROM companion_order
            WHERE deleted = 0 AND create_time BETWEEN #{start} AND #{end}
            GROUP BY d
            """)
    List<Map<String, Object>> selectDailyOrderTrend(@Param("start") LocalDateTime start,
                                                      @Param("end") LocalDateTime end);

    /**
     * 按天分组的投诉数（COMPLAINT 趋势）。
     *
     * @return 每行形如 {@code {d: 2026-09-15, cnt: 3}}
     */
    @Select("""
            SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS d,
                   COUNT(DISTINCT order_id) AS cnt
            FROM complaint
            WHERE deleted = 0 AND create_time BETWEEN #{start} AND #{end}
            GROUP BY d
            """)
    List<Map<String, Object>> selectDailyComplaintTrend(@Param("start") LocalDateTime start,
                                                         @Param("end") LocalDateTime end);

    /**
     * 按天分组的评价条数（REVIEW 趋势）。
     *
     * @return 每行形如 {@code {d: 2026-09-15, cnt: 4}}
     */
    @Select("""
            SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS d,
                   COUNT(*) AS cnt
            FROM order_review
            WHERE deleted = 0 AND is_valid = 1 AND create_time BETWEEN #{start} AND #{end}
            GROUP BY d
            """)
    List<Map<String, Object>> selectDailyReviewTrend(@Param("start") LocalDateTime start,
                                                      @Param("end") LocalDateTime end);

    /**
     * 按天分组的服药任务数（MEDICATION 趋势）。
     *
     * <p>三桶判定只出现一次：内层子查询算出 {@code bucket}，外层再从<b>同一个</b>
     * {@code bucket} 派生 taken / missed / pending —— 恒有
     * {@code taken + missed + pending == total}。</p>
     *
     * @return 每行形如 {@code {d: 2026-09-15, total: 50, taken: 46, missed: 4, pending: 0}}
     */
    @Select("""
            SELECT d, COUNT(*) AS total,
                   SUM(CASE WHEN bucket = 'TAKEN' THEN 1 ELSE 0 END) AS taken,
                   SUM(CASE WHEN bucket = 'MISSED' THEN 1 ELSE 0 END) AS missed,
                   SUM(CASE WHEN bucket = 'PENDING' THEN 1 ELSE 0 END) AS pending
            FROM (
              SELECT DATE_FORMAT(plan_time, '%Y-%m-%d') AS d,
            """ + MedicationBucketSql.CASE_BUCKET + """
              AS bucket
              FROM medication_task
              WHERE deleted = 0 AND plan_time BETWEEN #{start} AND #{end}
            ) t
            GROUP BY d
            """)
    List<Map<String, Object>> selectDailyMedicationTrend(@Param("start") LocalDateTime start,
                                                          @Param("end") LocalDateTime end);

    /**
     * 陪诊员维度的投诉数 / 接单数（异常中心「投诉率异常陪诊员」的数据源）。
     *
     * <p>接单数与投诉数<b>取同一个回溯区间</b>，否则分子分母不是同一段时间，
     * 算出来的「投诉率」没有意义。</p>
     *
     * @return 每行形如 {@code {companionId: 320, complaintCount: 2, orderCount: 6}}
     */
    @Select("""
            SELECT c.target_user_id AS companionId,
                   COUNT(DISTINCT c.order_id) AS complaintCount,
                   COALESCE(o.orderCount, 0) AS orderCount
            FROM complaint c
            LEFT JOIN (
                SELECT companion_id, COUNT(*) AS orderCount
                FROM companion_order
                WHERE deleted = 0 AND companion_id IS NOT NULL
                  AND create_time BETWEEN #{lookbackStart} AND #{lookbackEnd}
                GROUP BY companion_id
            ) o ON o.companion_id = c.target_user_id
            WHERE c.deleted = 0 AND c.target_role = 'COMPANION'
              AND c.create_time BETWEEN #{lookbackStart} AND #{lookbackEnd}
            GROUP BY c.target_user_id, o.orderCount
            """)
    List<Map<String, Object>> selectCompanionComplaintRates(@Param("lookbackStart") LocalDateTime lookbackStart,
                                                            @Param("lookbackEnd") LocalDateTime lookbackEnd);

    /**
     * 区间内的漏服任务数（异常中心「用药漏服异常」的数量）。
     *
     * <p>三桶口径与服药日历完全一致（{@link MedicationBucketSql#CASE_BUCKET}）。</p>
     *
     * @return 漏服任务数；无漏服时为 0
     */
    @Select("""
            SELECT COUNT(*)
            FROM (
              SELECT
            """ + MedicationBucketSql.CASE_BUCKET + """
              AS bucket
              FROM medication_task
              WHERE deleted = 0 AND plan_time BETWEEN #{start} AND #{end}
            ) t
            WHERE t.bucket = 'MISSED'
            """)
    Integer selectMissedMedicationCount(@Param("start") LocalDateTime start,
                                         @Param("end") LocalDateTime end);

    /**
     * 区间内最近漏服任务所属的订单（异常中心「用药漏服异常」的处理入口定位）。
     *
     * <p>⚠️ {@code medication_task} <b>没有</b> {@code order_id} 列（服药任务挂在
     * 老人档案上，与订单是多对多），所以这里按「同一老人」关联订单：
     * 漏服的是某位老人的服药任务，处理入口定位到该老人最近的订单。
     * 关联不到订单（老人还没下过单）时该行自然消失。</p>
     *
     * @param limit 最多返回多少条
     * @return 每行形如 {@code {orderId: 1019, lastPlanTime: 2026-09-16 08:00:00}}，
     *         按最近漏服时间降序
     */
    @Select("""
            SELECT o.id AS orderId, MAX(x.plan_time) AS lastPlanTime
            FROM (
              SELECT elder_id, plan_time,
            """ + MedicationBucketSql.CASE_BUCKET + """
              AS bucket
              FROM medication_task
              WHERE deleted = 0 AND plan_time BETWEEN #{start} AND #{end}
            ) x
            JOIN companion_order o ON o.deleted = 0 AND o.elder_id = x.elder_id
            WHERE x.bucket = 'MISSED'
            GROUP BY o.id
            ORDER BY lastPlanTime DESC
            LIMIT #{limit}
            """)
    List<Map<String, Object>> selectRecentMissedOrders(@Param("start") LocalDateTime start,
                                                        @Param("end") LocalDateTime end,
                                                        @Param("limit") int limit);

    /* ================================================================== */
    /* V3.6 数据闭环                                                        */
    /* ================================================================== */

    /**
     * 区间内的用药任务三桶聚合（用药任务数 / 漏服率的数据源）。
     *
     * <p>三桶判定<b>只出现一次</b>：内层子查询算出 {@code bucket}，
     * 外层再从<b>同一个</b> {@code bucket} 派生 taken / missed / pending ——
     * 恒有 {@code taken + missed + pending == total}，与服药日历同源
     * （{@link MedicationBucketSql#CASE_BUCKET}，ADR-0014）。</p>
     *
     * @return 一行形如 {@code {total: 120, taken: 110, missed: 6, pending: 4}}
     */
    @Select("""
            SELECT COUNT(*) AS total,
                   COALESCE(SUM(CASE WHEN bucket = 'TAKEN' THEN 1 ELSE 0 END), 0) AS taken,
                   COALESCE(SUM(CASE WHEN bucket = 'MISSED' THEN 1 ELSE 0 END), 0) AS missed,
                   COALESCE(SUM(CASE WHEN bucket = 'PENDING' THEN 1 ELSE 0 END), 0) AS pending
            FROM (
              SELECT
            """ + MedicationBucketSql.CASE_BUCKET + """
              AS bucket
              FROM medication_task
              WHERE deleted = 0 AND plan_time BETWEEN #{start} AND #{end}
            ) t
            """)
    Map<String, Object> selectMedicationAggregates(@Param("start") LocalDateTime start,
                                                    @Param("end") LocalDateTime end);

    /**
     * 区间内被管理员改派的订单数（改派率的数据源）。
     *
     * <p>分子取 {@code admin_oper_log} 里 {@code oper_type = 'REASSIGN_ORDER'}
     * 的记录，<b>按订单去重</b>（同一单被改派多次只算一单）——
     * 否则运营「多改派几次」就能把改派率刷上去。</p>
     *
     * <p>⚠️ 分子按<b>操作日志时间</b>过滤，分母（区间订单数）按<b>下单时间</b>过滤：
     * 改派这件事本身只发生在操作日志里，订单表里没有「被改派过」的痕迹。
     * 两个时间字段不同源这件事写在指标字典的公式里，不藏着。</p>
     *
     * @return 改派订单数；无改派记录时为 0
     */
    @Select("""
            SELECT COUNT(DISTINCT target_id)
            FROM admin_oper_log
            WHERE deleted = 0 AND oper_type = 'REASSIGN_ORDER'
              AND create_time BETWEEN #{start} AND #{end}
            """)
    Integer selectReassignedOrderCount(@Param("start") LocalDateTime start,
                                        @Param("end") LocalDateTime end);

    /**
     * 区间内「至今无人接」的订单及其就诊时间（供给缺口分析的数据源）。
     *
     * <p>「无人接」= 当前状态仍是 {@code PENDING}（V3.6 · 偏差 D-3.6-1）。
     * 桶按<b>就诊时间</b>落（{@code visit_time} 的周内第几天 × 小时），
     * 而不是按下单时间 —— 运营要判断的是「这个时段有没有人接得住」，
     * 关心的是订单<b>发生在哪个时段</b>。</p>
     *
     * @return 每行形如 {@code {id: 1007, visitTime: 2026-09-14 09:00:00}}
     */
    @Select("""
            SELECT id, visit_time AS visitTime
            FROM companion_order
            WHERE deleted = 0 AND status = 'PENDING' AND visit_time IS NOT NULL
              AND visit_time BETWEEN #{start} AND #{end}
            """)
    List<Map<String, Object>> selectPendingOrderVisitTimes(@Param("start") LocalDateTime start,
                                                           @Param("end") LocalDateTime end);

    /**
     * 全部陪诊员的结构化可服务时段（供给缺口分析的数据源）。
     *
     * <p>只取结构化表；存量自由文本由 Service 侧用
     * {@code LegacySlotParser} 回退解析（V3.4 的回退路径）。</p>
     *
     * <p>⚠️ <b>必须过滤资质与工作状态</b>：没通过审核的陪诊员接不了单，
     * 休息 / 离线的陪诊员也不接新单。把他们算作「供给」会让缺口被低估 ——
     * 平台会以为「这个时段有人」，而实际上没人会接。</p>
     *
     * @return 每行形如 {@code {companionId: 301, weekday: 1, startTime: "08:00:00", endTime: "18:00:00"}}
     */
    @Select("""
            SELECT s.companion_id AS companionId, s.weekday,
                   TIME_FORMAT(s.start_time, '%H:%i:%s') AS startTime,
                   TIME_FORMAT(s.end_time, '%H:%i:%s') AS endTime
            FROM companion_available_slot s
            JOIN companion_profile p ON p.user_id = s.companion_id
                                    AND p.deleted = 0
                                    AND p.audit_status = 'APPROVED'
                                    AND p.work_status = 'AVAILABLE'
            WHERE s.deleted = 0
            ORDER BY s.companion_id, s.weekday, s.start_time
            """)
    List<Map<String, Object>> selectCompanionServiceSlots();

    /**
     * 全部陪诊员的自由文本可服务时段（供给缺口分析的回退数据源）。
     *
     * <p>⚠️ 同样<b>必须过滤资质与工作状态</b>（理由见
     * {@link #selectCompanionServiceSlots()}）。</p>
     *
     * @return 每行形如 {@code {companionId: 301, availableTime: "周一至周五 08:00-18:00"}}
     */
    @Select("""
            SELECT user_id AS companionId, available_time AS availableTime
            FROM companion_profile
            WHERE deleted = 0
              AND audit_status = 'APPROVED'
              AND work_status = 'AVAILABLE'
            ORDER BY user_id
            """)
    List<Map<String, Object>> selectCompanionAvailableTimes();
}
