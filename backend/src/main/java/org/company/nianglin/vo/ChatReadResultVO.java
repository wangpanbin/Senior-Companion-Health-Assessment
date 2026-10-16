package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 已读上报结果（{@code POST /api/chat/{orderId}/read}，{@code docs/api/11-order-chat.md} §4）。
 *
 * @author 银龄伴诊团队
 * @since M13
 */
@Data
@Accessors(chain = true)
@Schema(description = "已读上报结果")
public class ChatReadResultVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "上报后的剩余未读数（通常为 0）", example = "0")
    private Integer unreadCount;
}
