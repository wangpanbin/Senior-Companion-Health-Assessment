package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 聊天未读总数（{@code GET /api/chat/unread-total}，{@code docs/api/11-order-chat.md} §5）。
 *
 * <p>供「消息」页入口卡与角标兜底轮询（与站内信 60s 轮询同节奏）。</p>
 *
 * @author 银龄伴诊团队
 * @since M13
 */
@Data
@Accessors(chain = true)
@Schema(description = "聊天未读总数")
public class ChatUnreadTotalVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "跨所有我会话的未读总数", example = "5")
    private Integer total;
}
