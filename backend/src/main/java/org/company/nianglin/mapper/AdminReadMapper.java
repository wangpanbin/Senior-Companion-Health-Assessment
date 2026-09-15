package org.company.nianglin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 管理后台的跨表只读查询（对应 M9）。
 *
 * <h3>为什么不写进各实体自己的 Mapper</h3>
 *
 * <p>{@code SysUserMapper} / {@code ComplaintMapper} 等都是 M1 由
 * {@code gen_entity.py} 从 {@code V1__init_schema.sql} <b>单向生成</b>的产物，
 * 往里加自定义方法会在下次重跑生成器时被抹掉（同 {@code OrderReadMapper} 的理由）。
 * 因此跨表聚合一律放进独立接口。</p>
 *
 * <h3>每一条都显式写 {@code deleted = 0}</h3>
 *
 * <p>原生 {@code @Select} 不经 MyBatis-Plus 的 {@code @TableLogic} 注入，
 * 少写这一句，被逻辑删除的数据就会重新计入列表与统计 ——
 * 这类错误在代码评审里看不出来，只能靠对账数字发现。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Mapper
public interface AdminReadMapper {

    /**
     * 批量统计用户的关联订单数。
     *
     * <p>「关联」对三种角色含义不同，因此是三段 {@code UNION ALL}：</p>
     * <ul>
     *   <li>家属：{@code companion_order.family_id} = 用户 ID（他下的单）</li>
     *   <li>陪诊员：{@code companion_order.companion_id} = 用户 ID（他接的单）</li>
     *   <li>老人：{@code elder_profile.user_id} = 用户 ID
     *       —— <b>不能直接用 {@code order.elder_id}</b>，
     *       那是档案 ID 而不是用户 ID，两者在库里是两套编号</li>
     * </ul>
     *
     * <p>用 {@code UNION ALL} 而不是 {@code UNION}：同一个人可能既是家属又是陪诊员
     * （被同一个订单同时关联），但那样他确实对应两条关联记录，
     * 去重会让「关联订单数」与列表里看到的行数对不上。</p>
     *
     * @param userIds 用户 ID 集合，调用方保证非空
     * @return 每行形如 {@code {uid: 101, cnt: 12}}；没有订单的用户不会出现在结果里
     */
    @Select("""
            <script>
            SELECT t.uid AS uid, COUNT(*) AS cnt
            FROM (
                SELECT family_id AS uid
                FROM companion_order
                WHERE deleted = 0 AND family_id IN
                <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                UNION ALL
                SELECT companion_id AS uid
                FROM companion_order
                WHERE deleted = 0 AND companion_id IN
                <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                UNION ALL
                SELECT e.user_id AS uid
                FROM companion_order o
                JOIN elder_profile e ON e.id = o.elder_id
                WHERE o.deleted = 0 AND e.user_id IN
                <foreach collection="userIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ) t
            GROUP BY t.uid
            </script>
            """)
    List<Map<String, Object>> selectOrderCountsByUserIds(@Param("userIds") Collection<Long> userIds);

    /**
     * 资质申请的各状态数量（整表口径，与分页和筛选无关）。
     *
     * <p>管理端标签栏要显示「待审核 5 / 已通过 6 / 已驳回 1」，这三个数字
     * 必须是整表的，而不是当前筛选结果里的 —— 否则管理员一筛「已通过」，
     * 待审核的角标就变成了 0，看起来像「待办的都清空了」。</p>
     *
     * @return 每行形如 {@code {status: PENDING, cnt: 5}}
     */
    @Select("""
            SELECT audit_status AS status, COUNT(*) AS cnt
            FROM companion_audit_record
            WHERE deleted = 0
            GROUP BY audit_status
            """)
    List<Map<String, Object>> selectAuditStatusCounts();

    /**
     * 批量取订单的最新一条投诉 ID。
     *
     * <p>取 {@code MAX(id)} 而不是「未结案的那条」：管理端列表只需要知道
     * 「这一单有纠纷，点进去看」，具体是哪一条由详情页决定。
     * 用 MAX(id) 是稳定的（自增主键），而且一条 SQL 就能覆盖整页订单，
     * 不必为「有没有未结案投诉」再查一次。</p>
     *
     * @param orderIds 订单 ID 集合，调用方保证非空
     * @return 每行形如 {@code {orderId: 1001, complaintId: 3001}}；无投诉的订单缺席
     */
    @Select("""
            <script>
            SELECT order_id AS orderId, MAX(id) AS complaintId
            FROM complaint
            WHERE deleted = 0 AND order_id IN
            <foreach collection="orderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY order_id
            </script>
            """)
    List<Map<String, Object>> selectLatestComplaintIds(@Param("orderIds") Collection<Long> orderIds);
}
