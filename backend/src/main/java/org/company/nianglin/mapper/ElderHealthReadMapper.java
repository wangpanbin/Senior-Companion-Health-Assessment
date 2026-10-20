package org.company.nianglin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.company.nianglin.constant.MedicationBucketSql;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 老人健康协同中心的跨表只读聚合查询（V3.2 · FR-Overview / FR-Summary）。
 *
 * <h3>为什么单独一个接口</h3>
 *
 * <p>{@code CompanionOrderMapper} / {@code MedicationTaskMapper} 等是 M1 由
 * {@code gen_entity.py} 从 DDL <b>单向生成</b>的产物。往里加自定义方法，
 * 下次任何人重跑生成器就会把它抹掉 —— 而编译器不会提醒。
 * 所以自定义查询一律放进独立接口（与 {@code OrderReadMapper} 同理）。</p>
 *
 * <h3>三条口径铁律（与 M10 一致）</h3>
 *
 * <ol>
 *   <li>原生 {@code @Select} 不经 MyBatis-Plus 的逻辑删除注入，
 *       <b>每条都要手写 {@code deleted = 0}</b>；</li>
 *   <li>「已完成」= {@code COMPLETED} + {@code REVIEWED}；</li>
 *   <li>时间区间闭区间，结束日用「次日零点」作为开区间上界。</li>
 * </ol>
 *
 * <h3>用药三桶只在 SQL 里判定一次</h3>
 *
 * <p>聚合查询把 {@link MedicationBucketSql#CASE_BUCKET} 放进内层子查询，
 * 外层再从<b>同一个</b> {@code bucket} 派生 taken / missed / pending ——
 * 三个桶不可能彼此打架。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@Mapper
public interface ElderHealthReadMapper {

    /**
     * 家属名下有效绑定的老人（连同关系标签）。
     *
     * <p>只按 {@code family_id + status='BOUND'} 过滤，
     * 解绑后自然不再出现。</p>
     *
     * @param familyId 当前登录家属的用户 ID
     * @return 每行形如 {@code {elderId: 301, name: 张三, gender: MALE, birthDate: 1944-05-01, relation: SON}}
     */
    @Select("""
            SELECT r.elder_id AS elderId,
                   p.name AS name,
                   p.gender AS gender,
                   p.birth_date AS birthDate,
                   r.relation AS relation
            FROM family_elder_relation r
            JOIN elder_profile p ON p.id = r.elder_id AND p.deleted = 0
            WHERE r.deleted = 0 AND r.family_id = #{familyId} AND r.status = 'BOUND'
            ORDER BY r.id ASC
            """)
    List<Map<String, Object>> selectFamilyElders(@Param("familyId") Long familyId);

    /**
     * 区间内每位老人的用药三桶计数。
     *
     * @param elderIds 老人档案 ID 集合
     * @param start    区间开始日（含）
     * @param end      区间结束日（含）
     * @return 每行形如 {@code {elderId: 301, total: 7, taken: 5, missed: 1, pending: 1}}
     */
    @Select("""
            <script>
            SELECT elder_id AS elderId,
                   COUNT(*) AS total,
                   SUM(CASE WHEN bucket = 'TAKEN' THEN 1 ELSE 0 END) AS taken,
                   SUM(CASE WHEN bucket = 'MISSED' THEN 1 ELSE 0 END) AS missed,
                   SUM(CASE WHEN bucket = 'PENDING' THEN 1 ELSE 0 END) AS pending
            FROM (
              SELECT elder_id,
            """ + MedicationBucketSql.CASE_BUCKET + """
              AS bucket
              FROM medication_task
              WHERE deleted = 0 AND elder_id IN
              <foreach collection="elderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                AND plan_date BETWEEN #{start} AND #{end}
            ) t
            GROUP BY elder_id
            </script>
            """)
    List<Map<String, Object>> selectMedicationBucketsByElder(@Param("elderIds") Collection<Long> elderIds,
                                                               @Param("start") LocalDate start,
                                                               @Param("end") LocalDate end);

    /**
     * 今日每位老人的用药计数（家庭健康协同首页用）。
     *
     * @param elderIds 老人档案 ID 集合
     * @param today    今天
     * @return 每行形如 {@code {elderId: 301, total: 3, taken: 2, pending: 1}}
     */
    @Select("""
            <script>
            SELECT elder_id AS elderId,
                   COUNT(*) AS total,
                   SUM(CASE WHEN bucket = 'TAKEN' THEN 1 ELSE 0 END) AS taken,
                   SUM(CASE WHEN bucket = 'MISSED' THEN 1 ELSE 0 END) AS missed,
                   SUM(CASE WHEN bucket = 'PENDING' THEN 1 ELSE 0 END) AS pending
            FROM (
              SELECT elder_id,
            """ + MedicationBucketSql.CASE_BUCKET + """
              AS bucket
              FROM medication_task
              WHERE deleted = 0 AND elder_id IN
              <foreach collection="elderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                AND plan_date = #{today}
            ) t
            GROUP BY elder_id
            </script>
            """)
    List<Map<String, Object>> selectTodayMedicationByElder(@Param("elderIds") Collection<Long> elderIds,
                                                             @Param("today") LocalDate today);

    /**
     * 区间内每位老人的就医统计。
     *
     * <p>{@code orderCount} / {@code companionCount} / {@code lastVisitAt} 只算
     * <b>已完成</b>的订单（{@code COMPLETED} + {@code REVIEWED}）；
     * {@code lastActivityAt} 则是区间内<b>任意状态</b>订单的最后一次就诊时间 ——
     * 「最近一次陪诊」与「最近一次已完成的陪诊」是两个不同的事实，不能混。</p>
     *
     * @param elderIds 老人档案 ID 集合
     * @param start    区间开始时刻
     * @param end      区间结束时刻
     * @return 每行形如
     *         {@code {elderId: 301, orderCount: 2, companionCount: 1, lastVisitAt: 2026-10-16 09:30:00,
     *         lastActivityAt: 2026-10-16 09:30:00}}
     */
    @Select("""
            <script>
            SELECT elder_id AS elderId,
                   SUM(CASE WHEN status IN ('COMPLETED', 'REVIEWED') THEN 1 ELSE 0 END) AS orderCount,
                   COUNT(DISTINCT CASE WHEN status IN ('COMPLETED', 'REVIEWED') THEN companion_id END)
                       AS companionCount,
                   MAX(CASE WHEN status IN ('COMPLETED', 'REVIEWED') THEN visit_time END) AS lastVisitAt,
                   MAX(visit_time) AS lastActivityAt
            FROM companion_order
            WHERE deleted = 0 AND elder_id IN
            <foreach collection="elderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              AND visit_time BETWEEN #{start} AND #{end}
            GROUP BY elder_id
            </script>
            """)
    List<Map<String, Object>> selectVisitStatsByElder(@Param("elderIds") Collection<Long> elderIds,
                                                        @Param("start") LocalDateTime start,
                                                        @Param("end") LocalDateTime end);

    /**
     * 每位老人的待处理事项计数。
     *
     * <p>三类都是<b>纯事实</b>：等接单的订单、还没评价的订单、履约风险已达高位的订单。
     * 不含任何健康判断（合规红线-1）。</p>
     *
     * @param elderIds 老人档案 ID 集合
     * @return 每行形如
     *         {@code {elderId: 301, waitingOrders: 1, toReviewOrders: 1, riskOrders: 0}}
     */
    @Select("""
            <script>
            SELECT elder_id AS elderId,
                   SUM(CASE WHEN status = 'PENDING' THEN 1 ELSE 0 END) AS waitingOrders,
                   SUM(CASE WHEN status = 'COMPLETED' THEN 1 ELSE 0 END) AS toReviewOrders,
                   SUM(CASE WHEN status = 'PENDING'
                             AND fulfillment_risk IN ('URGENT', 'MANUAL_REQUIRED')
                            THEN 1 ELSE 0 END) AS riskOrders
            FROM companion_order
            WHERE deleted = 0 AND elder_id IN
            <foreach collection="elderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY elder_id
            </script>
            """)
    List<Map<String, Object>> selectPendingCountsByElder(@Param("elderIds") Collection<Long> elderIds);
}
