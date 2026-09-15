package org.company.nianglin.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 站内信模块的跨表 / 聚合只读查询。
 *
 * <h3>为什么不加进 {@code InternalMessageMapper}</h3>
 *
 * <p>{@code InternalMessageMapper} 是 M1 由 {@code gen_entity.py} 从
 * {@code V1__init_schema.sql} 单向生成的产物。往里加自定义方法，
 * 下次任何人重跑生成器就会把它抹掉，而编译器不会提醒。
 * 与 {@code OrderReadMapper} 同样的理由，自定义 SQL 一律独立接口。</p>
 *
 * <h3>为什么未读聚合要单独一条 SQL</h3>
 *
 * <p>未读数按类型分组，接口每次刷新都会调（顶栏铃铛）。
 * 用 MyBatis-Plus 的 {@code selectList} 把未读消息全部拉回来再在内存里分组，
 * 一个重度用户攒了 300 条未读就要传 300 行 —— 而这里只要 9 行以内。
 * 顶栏数字是最频繁的读，必须在数据库里就收敛成聚合值。</p>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
@Mapper
public interface MessageReadMapper {

    /**
     * 某用户的未读数按消息类型分组。
     *
     * <p>返回的每一行只有两个键：{@code type} 与 {@code cnt}。
     * 过滤条件里 {@code receiver_deleted = 0} 是必须的 ——
     * 用户删掉的消息不该继续计入红点，否则「删了还有红点」是必然被投诉的交互 bug。</p>
     *
     * <p>⚠️ 条件里手写了 {@code deleted = 0}：原生 {@code @Select} 不经过
     * MyBatis-Plus 的逻辑删除注入，不写就是把自己的逻辑删除当不存在。</p>
     *
     * @param receiverId 收件人用户 ID
     * @return 形如 {@code [{type=ORDER_ACCEPTED, cnt=2}, ...]}
     */
    @Select("""
            SELECT type AS type, COUNT(*) AS cnt
            FROM internal_message
            WHERE receiver_id = #{receiverId}
              AND is_read = 0
              AND receiver_deleted = 0
              AND deleted = 0
            GROUP BY type
            """)
    List<Map<String, Object>> countUnreadGroupByType(@Param("receiverId") Long receiverId);

    /**
     * 某用户在指定类型列表中的未读数。
     *
     * <p>用于「全部已读」前的预检与缓存校正。类型列表为空时由调用方保证不走这里
     * —— 空的 {@code IN ()} 在 MySQL 里是语法错误。</p>
     */
    @Select("""
            <script>
            SELECT COUNT(*)
            FROM internal_message
            WHERE receiver_id = #{receiverId}
              AND is_read = 0
              AND receiver_deleted = 0
              AND deleted = 0
              AND type IN
            <foreach collection="types" item="t" open="(" separator="," close=")">#{t}</foreach>
            </script>
            """)
    Long countUnreadByTypes(@Param("receiverId") Long receiverId, @Param("types") List<String> types);
}
