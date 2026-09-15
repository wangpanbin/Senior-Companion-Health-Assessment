package org.company.nianglin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 统计与导出的聚合只读查询（对应 M10）。
 *
 * <h3>三条口径铁律，写在这里免得散落到 Service</h3>
 *
 * <ol>
 *   <li><b>{@code deleted = 0} 每一条都要写</b>：原生 {@code @Select}
 *       不经 MyBatis-Plus 的 {@code @TableLogic} 注入，漏写就会把已删除数据算进统计。</li>
 *   <li><b>「已完成」= {@code COMPLETED} + {@code REVIEWED}</b>：
 *       评价只是完成之后的后续动作，把已评价的单排除会让完成率
 *       随着用户评价而下降 —— 这是最容易被验收手工核对抓到的一类错误。</li>
 *   <li><b>时间区间用 {@code [start, end]} 闭区间</b>，由调用方把
 *       {@code endDate} 折算成当天 23:59:59 再传进来。</li>
 * </ol>
 *
 * <h3>为什么按天聚合而不是按粒度聚合</h3>
 *
 * <p>DAY / WEEK / MONTH 三种粒度共用一条 SQL，聚合到目标粒度在 Java 侧完成。
 * 「补齐没有数据的日期为 0」本来就必须在 Java 侧做
 * （{@code GROUP BY} 不会返回空桶），既然补齐在那里，分桶也放在那里，
 * 数据库只需要返回最细粒度的计数。</p>
 *
 * @author 银龄伴诊团队
 * @since M10
 */
@Mapper
public interface StatisticsMapper {

    /**
     * 区间内各状态订单数。
     *
     * @return 每行形如 {@code {status: COMPLETED, cnt: 96}}；没有订单的状态缺席
     */
    @Select("""
            SELECT status, COUNT(*) AS cnt
            FROM companion_order
            WHERE deleted = 0 AND create_time BETWEEN #{start} AND #{end}
            GROUP BY status
            """)
    List<Map<String, Object>> selectOrderCountsByStatus(@Param("start") LocalDateTime start,
                                                        @Param("end") LocalDateTime end);

    /** 区间内有过接单的陪诊员数（去重） */
    @Select("""
            SELECT COUNT(DISTINCT companion_id)
            FROM companion_order
            WHERE deleted = 0 AND companion_id IS NOT NULL
              AND create_time BETWEEN #{start} AND #{end}
            """)
    Integer selectActiveCompanionCount(@Param("start") LocalDateTime start,
                                       @Param("end") LocalDateTime end);

    /**
     * 平均接单耗时（分钟）：下单时间 → 接单时间的差值均值。
     *
     * <p>只统计已接单的订单（{@code accept_time IS NOT NULL}），
     * 否则未接单的那些会把均值拉垮到无意义的值。无数据时返回 {@code null}。</p>
     */
    @Select("""
            SELECT ROUND(AVG(TIMESTAMPDIFF(MINUTE, create_time, accept_time)))
            FROM companion_order
            WHERE deleted = 0 AND accept_time IS NOT NULL
              AND create_time BETWEEN #{start} AND #{end}
            """)
    Long selectAvgAcceptMinutes(@Param("start") LocalDateTime start,
                                @Param("end") LocalDateTime end);

    /** 用户总数（全量，不受区间影响） */
    @Select("SELECT COUNT(*) FROM sys_user WHERE deleted = 0")
    Integer selectUserTotalCount();

    /** 区间内新增用户数 */
    @Select("""
            SELECT COUNT(*)
            FROM sys_user
            WHERE deleted = 0 AND create_time BETWEEN #{start} AND #{end}
            """)
    Integer selectNewUserCount(@Param("start") LocalDateTime start,
                               @Param("end") LocalDateTime end);

    /**
     * 按角色统计用户数。
     *
     * @return 每行形如 {@code {role: FAMILY, cnt: 121}}；没有用户的角色缺席
     */
    @Select("""
            SELECT role, COUNT(*) AS cnt
            FROM sys_user
            WHERE deleted = 0
            GROUP BY role
            """)
    List<Map<String, Object>> selectUserCountsByRole();

    /**
     * 按天 + 状态统计订单数（趋势图的数据源）。
     *
     * @return 每行形如 {@code {d: 2026-09-15, status: COMPLETED, cnt: 13}}
     */
    @Select("""
            SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS d, status, COUNT(*) AS cnt
            FROM companion_order
            WHERE deleted = 0 AND create_time BETWEEN #{start} AND #{end}
            GROUP BY d, status
            """)
    List<Map<String, Object>> selectDailyOrderCounts(@Param("start") LocalDateTime start,
                                                     @Param("end") LocalDateTime end);

    /**
     * 陪诊员接单统计（排行榜的数据源）。
     *
     * <p>{@code INNER JOIN companion_profile} 而不只是 {@code WHERE role = 'COMPANION'}：
     * 只有资质档案里 {@code audit_status = 'APPROVED'} 的人才是真正的陪诊员
     * （文档 §4 实现要点第 1 条）。同时把评分一起取出来，
     * 免得为了 {@code metric = SCORE} 再查一次。</p>
     *
     * @return 每行形如
     *         {@code {companionId: 10088, orderCount: 22, completedCount: 21, score: 4.91}}
     */
    @Select("""
            SELECT o.companion_id AS companionId,
                   COUNT(*) AS orderCount,
                   SUM(CASE WHEN o.status IN ('COMPLETED', 'REVIEWED') THEN 1 ELSE 0 END) AS completedCount,
                   MAX(p.score) AS score
            FROM companion_order o
            JOIN companion_profile p ON p.user_id = o.companion_id
                 AND p.deleted = 0 AND p.audit_status = 'APPROVED'
            WHERE o.deleted = 0 AND o.companion_id IS NOT NULL
              AND o.create_time BETWEEN #{start} AND #{end}
            GROUP BY o.companion_id
            """)
    List<Map<String, Object>> selectCompanionRankRows(@Param("start") LocalDateTime start,
                                                      @Param("end") LocalDateTime end);

    /** 批量取陪诊员姓名（{@code companion_profile.real_name}） */
    @Select("""
            <script>
            SELECT user_id AS userId, real_name AS realName
            FROM companion_profile
            WHERE deleted = 0 AND user_id IN
            <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Map<String, Object>> selectCompanionNames(@Param("userIds") Collection<Long> userIds);

    /**
     * 按天统计服药任务（漏服率图表的数据源）。
     *
     * <p>{@code missed} 取 {@code was_missed = 1} 而不是 {@code status = 'MISSED'}：
     * 漏了之后补记的任务状态会变成「已服」，若按状态统计，
     * 家属会看到「一条漏服都没有」而漏服率却不是 0 ——
     * 数字与列表互相矛盾，是这类统计最容易被质疑的地方
     * （与 {@code MedicationCalendarVO} 的口径保持一致）。</p>
     *
     * @param elderId 可选，只看某位老人
     * @return 每行形如 {@code {d: 2026-09-15, total: 50, taken: 46, missed: 4}}
     */
    @Select("""
            <script>
            SELECT DATE_FORMAT(plan_time, '%Y-%m-%d') AS d,
                   COUNT(*) AS total,
                   SUM(CASE WHEN status = 'TAKEN' THEN 1 ELSE 0 END) AS taken,
                   SUM(CASE WHEN was_missed = 1 THEN 1 ELSE 0 END) AS missed
            FROM medication_task
            WHERE deleted = 0 AND plan_time BETWEEN #{start} AND #{end}
            <if test="elderId != null">AND elder_id = #{elderId}</if>
            GROUP BY d
            </script>
            """)
    List<Map<String, Object>> selectDailyMedicationCounts(@Param("start") LocalDateTime start,
                                                          @Param("end") LocalDateTime end,
                                                          @Param("elderId") Long elderId);

    /** 统计用：某位老人的姓名（脱敏由 VO 负责，这里只取原文） */
    @Select("SELECT name FROM elder_profile WHERE id = #{elderId} AND deleted = 0")
    String selectElderName(@Param("elderId") Long elderId);

    /** 陪诊员评分的直读（用于导出与核对，缓存版本见 {@code ReviewService}） */
    @Select("""
            SELECT ROUND(AVG(score), 2)
            FROM order_review
            WHERE deleted = 0 AND is_valid = 1 AND companion_id = #{companionId}
            """)
    BigDecimal selectAverageScore(@Param("companionId") Long companionId);
}
