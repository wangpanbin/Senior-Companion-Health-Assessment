package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.ChatMsgType;
import org.company.nianglin.entity.ChatMessage;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 聊天会话列表项（{@code GET /api/chat/conversations}，{@code docs/api/11-order-chat.md} §3）。
 *
 * <p>⚠️ {@code peerName} 脱敏（{@code MaskUtil.name()}），
 * 且<b>不返回手机号</b> —— 会话列表是扫一眼多个订单的场景，
 * 没有任何一条业务理由需要看到联系方式。</p>
 *
 * @author 银龄伴诊团队
 * @since M13
 */
@Data
@Accessors(chain = true)
@Schema(description = "聊天会话列表项")
public class ChatConversationVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "订单 ID（进入聊天室的路径参数）", example = "1001")
    private Long orderId;

    @Schema(description = "订单号", example = "NL20261015000001")
    private String orderNo;

    @Schema(description = "订单状态中文名", example = "服务中")
    private String orderStatusLabel;

    @Schema(description = "对方用户 ID", example = "3003")
    private Long peerUserId;

    @Schema(description = "对方姓名（脱敏）", example = "李*明")
    private String peerName;

    @Schema(description = "对方角色：FAMILY / COMPANION", example = "COMPANION")
    private String peerRole;

    @Schema(description = "就诊医院", example = "海南省人民医院")
    private String hospital;

    @Schema(description = "就诊时间", example = "2026-10-16 09:30:00")
    private LocalDateTime visitTime;

    @Schema(description = "最后一条消息预览，无消息时为空")
    private LastMessage lastMessage;

    @Schema(description = "我的未读数（对方发来且未读）", example = "2")
    private Integer unreadCount;

    /**
     * 最后一条消息的列表预览。
     *
     * <p>媒体消息固定「[图片] / [视频]」文案，<b>不含</b>用户输入的说明文字 ——
     * 防止长文本把列表行撑爆；正文在聊天室里看。</p>
     */
    @Data
    @Accessors(chain = true)
    @Schema(description = "最后一条消息预览")
    public static class LastMessage implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        @Schema(description = "消息类型：TEXT / IMAGE / VIDEO", example = "TEXT")
        private String msgType;

        @Schema(description = "消息类型中文名", example = "文字")
        private String msgTypeLabel;

        @Schema(description = "预览文本（≤30 字）", example = "今天辛苦了，老人中午…")
        private String preview;

        @Schema(description = "发送时间", example = "2026-10-16 12:30:05")
        private LocalDateTime createTime;
    }

    /** 由最后一条消息实体生成预览；{@code null} 表示会话尚无消息 */
    public static LastMessage lastOf(ChatMessage message) {
        if (message == null) {
            return null;
        }
        ChatMsgType type = ChatMsgType.of(message.getMsgType());
        String label = type == null ? message.getMsgType() : type.label();
        // 媒体消息不透传说明文字（见类注释）；兜底未知类型按原文截断
        String preview = switch (type == null ? ChatMsgType.TEXT : type) {
            case IMAGE -> "[图片]";
            case VIDEO -> "[视频]";
            case TEXT -> previewOf(message.getContent());
        };
        return new LastMessage()
                .setMsgType(message.getMsgType())
                .setMsgTypeLabel(label)
                .setPreview(preview)
                .setCreateTime(message.getCreateTime());
    }

    /** 截断到 30 字（列表单行展示的物理上限） */
    public static String previewOf(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        return trimmed.codePointCount(0, trimmed.length()) <= 30
                ? trimmed : trimmed.substring(0, trimmed.offsetByCodePoints(0, 29)) + "…";
    }
}
