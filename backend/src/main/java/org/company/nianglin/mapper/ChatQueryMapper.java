package org.company.nianglin.mapper;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.company.nianglin.entity.ChatMessage;
import org.company.nianglin.entity.CompanionOrder;

import java.util.List;
import java.util.Map;

@Mapper
public interface ChatQueryMapper {
    @Insert("""
            INSERT INTO chat_read_cursor (order_id, user_id, last_read_message_id, read_time)
            VALUES (#{orderId}, #{userId}, #{messageId}, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE
                read_time = IF(VALUES(last_read_message_id) > last_read_message_id, CURRENT_TIMESTAMP, read_time),
                last_read_message_id = GREATEST(last_read_message_id, VALUES(last_read_message_id))
            """)
    int advanceReadCursor(@Param("orderId") Long orderId, @Param("userId") Long userId,
                          @Param("messageId") long messageId);

    @Select("""
            SELECT o.* FROM companion_order o
            WHERE o.deleted = 0 AND o.companion_id IS NOT NULL
              AND ((#{role} = 'FAMILY' AND o.family_id = #{userId})
                OR (#{role} = 'COMPANION' AND o.companion_id = #{userId}))
            ORDER BY (SELECT MAX(m.id) FROM chat_message m
                      WHERE m.order_id = o.id AND m.deleted = 0) DESC, o.id DESC
            """)
    Page<CompanionOrder> conversations(Page<CompanionOrder> page, @Param("userId") Long userId,
                                       @Param("role") String role);

    @Select("""
            <script>
            SELECT o.* FROM companion_order o
            WHERE o.deleted = 0 AND o.companion_id IS NOT NULL
              AND EXISTS (SELECT 1 FROM chat_message m WHERE m.order_id = o.id AND m.deleted = 0)
            <if test="keyword != null and keyword != ''">
              AND (LOCATE(#{keyword}, o.order_no) > 0 OR LOCATE(#{keyword}, o.hospital) > 0)
            </if>
            ORDER BY (SELECT MAX(m.create_time) FROM chat_message m
                      WHERE m.order_id = o.id AND m.deleted = 0) DESC, o.id DESC
            </script>
            """)
    Page<CompanionOrder> adminOrders(Page<CompanionOrder> page, @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT m.* FROM chat_message m WHERE m.id IN (
              SELECT MAX(id) FROM chat_message WHERE deleted = 0 AND order_id IN
              <foreach collection="orderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              GROUP BY order_id
            )
            </script>
            """)
    List<ChatMessage> lastMessages(@Param("orderIds") List<Long> orderIds);

    @Select("""
            <script>
            SELECT m.order_id AS orderId, COUNT(*) AS messageCount,
                   SUM(CASE WHEN m.id > COALESCE(c.last_read_message_id, 0)
                            AND m.sender_id != #{userId} THEN 1 ELSE 0 END) AS unreadCount
            FROM chat_message m
            LEFT JOIN chat_read_cursor c ON c.order_id = m.order_id AND c.user_id = #{userId} AND c.deleted = 0
            WHERE m.deleted = 0 AND m.order_id IN
            <foreach collection="orderIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            GROUP BY m.order_id
            </script>
            """)
    List<Map<String, Object>> messageStats(@Param("orderIds") List<Long> orderIds, @Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*) FROM companion_order o
            JOIN chat_message m ON m.order_id = o.id AND m.deleted = 0
            LEFT JOIN chat_read_cursor c ON c.order_id = o.id AND c.user_id = #{userId} AND c.deleted = 0
            WHERE o.deleted = 0 AND o.companion_id IS NOT NULL
              AND ((#{role} = 'FAMILY' AND o.family_id = #{userId})
                OR (#{role} = 'COMPANION' AND o.companion_id = #{userId}))
              AND m.id > COALESCE(c.last_read_message_id, 0) AND m.sender_id != #{userId}
            """)
    long unreadTotal(@Param("userId") Long userId, @Param("role") String role);

    @Select("""
            SELECT COALESCE(MAX(id), 0) FROM chat_message WHERE order_id = #{orderId} AND deleted = 0
            """)
    long maxMessageId(@Param("orderId") Long orderId);

    @Select("""
            SELECT COUNT(*) FROM chat_message m
            LEFT JOIN chat_read_cursor c ON c.order_id = m.order_id AND c.user_id = #{userId} AND c.deleted = 0
            WHERE m.order_id = #{orderId} AND m.deleted = 0
              AND m.id > COALESCE(c.last_read_message_id, 0) AND m.sender_id != #{userId}
            """)
    long unreadCount(@Param("orderId") Long orderId, @Param("userId") Long userId);
}
