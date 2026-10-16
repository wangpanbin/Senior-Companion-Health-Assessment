package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.ChatMsgType;
import org.company.nianglin.entity.ChatMessage;

import java.time.LocalDateTime;

/**
 * 聊天消息出参（{@code docs/api/11-order-chat.md} §一）。
 *
 * <p>同时用于 REST 响应与 WS {@code CHAT_MESSAGE} 推送的载荷字段。</p>
 *
 * <p>⚠️ 消息流不返回手机号；{@code senderRole} 是落库时的角色快照，
 * 不回查 {@code sys_user.role}（角色封禁/变更后历史消息仍保持当时的发送身份）。</p>
 *
 * @author 银龄伴诊团队
 * @since M13
 */
@Data
@Accessors(chain = true)
@Schema(description = "聊天消息")
public class ChatMessageVO {

    @Schema(description = "消息 ID（会话内游标锚点，单调递增）", example = "90001")
    private Long messageId;

    @Schema(description = "订单 ID", example = "1001")
    private Long orderId;

    @Schema(description = "发送人用户 ID", example = "2002")
    private Long senderId;

    @Schema(description = "发送人角色快照：FAMILY / COMPANION", example = "FAMILY")
    private String senderRole;

    @Schema(description = "消息类型：TEXT / IMAGE / VIDEO", example = "TEXT")
    private String msgType;

    @Schema(description = "消息类型中文名", example = "文字")
    private String msgTypeLabel;

    @Schema(description = "文字正文；媒体消息为说明文字（可为空）", example = "今天辛苦了，老人中午吃饭了吗？")
    private String content;

    @Schema(description = "媒体消息的文件标识（sys_file.file_id）", example = "f_8001")
    private String fileId;

    @Schema(description = "媒体 URL（/uploads/... 静态直链）", example = "/uploads/202610/def456.jpg")
    private String fileUrl;

    @Schema(description = "媒体字节数", example = "204800")
    private Long fileSize;

    @Schema(description = "发送时间", example = "2026-10-16 12:30:05")
    private LocalDateTime createTime;

    public static ChatMessageVO of(ChatMessage message) {
        if (message == null) {
            return null;
        }
        ChatMsgType type = ChatMsgType.of(message.getMsgType());
        return new ChatMessageVO()
                .setMessageId(message.getId())
                .setOrderId(message.getOrderId())
                .setSenderId(message.getSenderId())
                .setSenderRole(message.getSenderRole())
                .setMsgType(message.getMsgType())
                .setMsgTypeLabel(type == null ? message.getMsgType() : type.label())
                .setContent(message.getContent())
                .setFileId(message.getFileId())
                .setFileUrl(message.getFileUrl())
                .setFileSize(message.getFileSize())
                .setCreateTime(message.getCreateTime());
    }
}
