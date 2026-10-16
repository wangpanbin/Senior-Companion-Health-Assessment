package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;

/**
 * ChatMessage —— 对应表 {@code chat_message}。
 *
 * <p>订单聊天消息表（会话=订单，ADR-0011）</p>
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
@TableName("chat_message")
public class ChatMessage extends BaseEntity {

    /**
     * 关联 companion_order.id（会话身份 = 订单）
     */
    @Schema(description = "关联 companion_order.id（会话身份 = 订单）")
    private Long orderId;

    /**
     * 发送人用户 ID（仅本单 FAMILY/COMPANION）
     */
    @Schema(description = "发送人用户 ID（仅本单 FAMILY/COMPANION）")
    private Long senderId;

    /**
     * 发送人角色快照：FAMILY / COMPANION
     */
    @Schema(description = "发送人角色快照：FAMILY / COMPANION")
    private String senderRole;

    /**
     * 消息类型：TEXT-文字 / IMAGE-图片 / VIDEO-视频
     */
    @Schema(description = "消息类型：TEXT-文字 / IMAGE-图片 / VIDEO-视频")
    private String msgType;

    /**
     * TEXT 正文（≤1000 字）；媒体消息可传说明文字
     */
    @Schema(description = "TEXT 正文（≤1000 字）；媒体消息可传说明文字")
    private String content;

    /**
     * 媒体消息必填，关联 sys_file.file_id（须 biz_type=CHAT 且 uploader_id=sender_id）
     */
    @Schema(description = "媒体消息必填，关联 sys_file.file_id（须 biz_type=CHAT 且 uploader_id=sender_id）")
    private String fileId;

    /**
     * 媒体 URL 快照（/uploads/...），展示用冗余
     */
    @Schema(description = "媒体 URL 快照（/uploads/...），展示用冗余")
    private String fileUrl;

    /**
     * 媒体字节数
     */
    @Schema(description = "媒体字节数")
    private Long fileSize;

}
