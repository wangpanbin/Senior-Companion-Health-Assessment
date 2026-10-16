package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/**
 * 聊天已读上报入参（{@code POST /api/chat/{orderId}/read}）。
 *
 * <p>对应 {@code docs/api/11-order-chat.md} §4。天然幂等：
 * 游标只前进不后退，重复上报同值是空操作。</p>
 *
 * @author 银龄伴诊团队
 * @since M13
 */
@Data
@Schema(description = "聊天已读上报入参")
public class ChatReadDTO {

    @Schema(description = "当前已读到的最大消息 ID", example = "90001", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "lastMessageId 不能为空")
    @PositiveOrZero(message = "lastMessageId 不能为负数")
    private Long lastMessageId;
}
