package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.time.LocalDateTime;

/**
 * ChatReadCursor —— 对应表 {@code chat_read_cursor}。
 *
 * <p>订单聊天已读游标表（ADR-0011）</p>
 *
 * <p>主键与审计字段（id / createTime / updateTime / deleted）继承自 {@link BaseEntity}。</p>
 *
 * <p>⚠️ 本类为持久化实体，禁止直接作为接口返回值；对外统一转 VO，
 * 避免密码、身份证号、完整手机号等敏感字段外泄。</p>
 *
 * @since M1
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("chat_read_cursor")
public class ChatReadCursor extends BaseEntity {

    /**
     * 订单 ID
     */
    @Schema(description = "订单 ID")
    private Long orderId;

    /**
     * 已读人用户 ID（会话双方各一条）
     */
    @Schema(description = "已读人用户 ID（会话双方各一条）")
    private Long userId;

    /**
     * 已读游标：≤ 该 id 的对方消息视为已读，只前进不后退
     */
    @Schema(description = "已读游标：≤ 该 id 的对方消息视为已读，只前进不后退")
    private Long lastReadMessageId;

    /**
     * 最近上报时间
     */
    @Schema(description = "最近上报时间")
    private LocalDateTime readTime;

}
