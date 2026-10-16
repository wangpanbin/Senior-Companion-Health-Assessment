package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 聊天历史出参（{@code GET /api/chat/{orderId}/messages}，{@code docs/api/11-order-chat.md} §2）。
 *
 * <p>{@code records} 按 {@code messageId} <b>降序</b>（最新在前），前端自行反转为正序展示；
 * {@code nextCursor} = 本页最旧一条的 id，下一次请求作 {@code cursor} 传入；
 * {@code hasMore=false} 表示已到最早一条。</p>
 *
 * <p>{@code peer*} 与 {@code myRole} 供聊天室头部直接渲染，
 * 免得前端再拼一次「我是谁、对面是谁」。</p>
 *
 * @author 银龄伴诊团队
 * @since M13
 */
@Data
@Accessors(chain = true)
@Schema(description = "聊天历史出参")
public class ChatHistoryVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "本页消息（按 messageId 降序）")
    private List<ChatMessageVO> records;

    @Schema(description = "下一次拉取的游标（本页最旧一条的 id）；无消息时为空", example = "90001")
    private Long nextCursor;

    @Schema(description = "是否还有更早的消息", example = "true")
    private Boolean hasMore;

    @Schema(description = "对方用户 ID", example = "3003")
    private Long peerUserId;

    @Schema(description = "对方姓名（脱敏）", example = "李*明")
    private String peerName;

    @Schema(description = "对方角色：FAMILY / COMPANION", example = "COMPANION")
    private String peerRole;

    @Schema(description = "我的角色：FAMILY / COMPANION / ELDER / ADMIN", example = "FAMILY")
    private String myRole;
}
