package org.company.nianglin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 智能调度的聚合只读查询（V3.4 · FR-Service）。
 *
 * <h3>三条口径铁律</h3>
 *
 * <ol>
 *   <li><b>{@code deleted = 0} 每条都要写</b>（原生 {@code @Select} 不经 {@code @TableLogic} 注入）；</li>
 *   <li><b>「已完成」= {@code COMPLETED} + {@code REVIEWED}</b>；</li>
 *   <li><b>资质与工作状态分开取</b>：过滤链第一步看工作状态、第二步看资质，
 *       两者都是硬条件，但排查时需要知道<b>到底卡在哪一步</b> ——
 *       所以候选查询把两个字段都查出来，由 Service 判断。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
@Mapper
public interface MatchingMapper {

    /**
     * 全部陪诊员候选（含资质 / 工作状态），供过滤链逐条判定。
     *
     * <p>不在 SQL 里过滤资质与工作状态，是为了让 Service 能区分
     * 「资质没通过」与「今天不接单」—— 两种情况的处置完全不同。</p>
     *
     * @return 每行形如
     *         {@code {userId: 301, realName: 李建军, regionCode: HAIKOU_MEILAN,
     *         serviceArea: 海口市美兰区, availableTime: 周一至周五 08:00-18:00,
     *         longitude: 110.31, latitude: 20.02,
     *         auditStatus: APPROVED, workStatus: AVAILABLE, score: 4.80}}
     */
    @Select("""
            SELECT user_id AS userId, real_name AS realName,
                   service_region_code AS regionCode, service_area AS serviceArea,
                   available_time AS availableTime,
                   longitude, latitude, audit_status AS auditStatus,
                   work_status AS workStatus, score
            FROM companion_profile
            WHERE deleted = 0
            ORDER BY user_id
            """)
    List<Map<String, Object>> selectCandidates();

    /**
     * 批量取可服务时段。
     *
     * @param companionIds 陪诊员 ID 集合
     * @return 每行形如 {@code {companionId: 301, weekday: 1, startTime: 08:00:00, endTime: 18:00:00}}
     */
    @Select("""
            <script>
            SELECT companion_id AS companionId, weekday,
                   TIME_FORMAT(start_time, '%H:%i:%s') AS startTime,
                   TIME_FORMAT(end_time, '%H:%i:%s') AS endTime
            FROM companion_available_slot
            WHERE deleted = 0 AND companion_id IN
            <foreach collection="companionIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY companion_id, weekday, start_time
            </script>
            """)
    List<Map<String, Object>> selectSlots(@Param("companionIds") Collection<Long> companionIds);

    /**
     * 批量取「陪诊员 × 科室」的已完成单数（服务能力维度的数据源）。
     *
     * @param companionIds 陪诊员 ID 集合
     * @return 每行形如 {@code {companionId: 301, department: 心血管内科, cnt: 12}}
     */
    @Select("""
            <script>
            SELECT companion_id AS companionId, department, COUNT(*) AS cnt
            FROM companion_order
            WHERE deleted = 0 AND companion_id IN
            <foreach collection="companionIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              AND status IN ('COMPLETED', 'REVIEWED')
              AND department IS NOT NULL AND department &lt;&gt; ''
            GROUP BY companion_id, department
            ORDER BY companion_id, cnt DESC
            </script>
            """)
    List<Map<String, Object>> selectDepartmentOrderCounts(@Param("companionIds") Collection<Long> companionIds);

    /**
     * 批量取若干陪诊员在手订单的就诊时间（档期冲突判定用）。
     *
     * <p>在手 = {@code ACCEPTED}（已接单待服务）+ {@code IN_SERVICE}（服务中）。
     * 已完成 / 已取消 / 待接单都不占档期。</p>
     *
     * @param companionIds 陪诊员 ID 集合
     * @return 每行形如 {@code {companionId: 301, orderId: 1007, visitTime: 2026-09-14 09:00:00}}
     */
    @Select("""
            <script>
            SELECT companion_id AS companionId, id AS orderId, visit_time AS visitTime
            FROM companion_order
            WHERE deleted = 0 AND companion_id IN
            <foreach collection="companionIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              AND status IN ('ACCEPTED', 'IN_SERVICE')
            ORDER BY companion_id, visit_time
            </script>
            """)
    List<Map<String, Object>> selectInFlightOrders(@Param("companionIds") Collection<Long> companionIds);

}
