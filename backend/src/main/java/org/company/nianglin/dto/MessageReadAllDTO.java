package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 全部已读入参（{@code PUT /api/message/read-all}）。
 *
 * <p>{@code docs/api/07-message.md} §4 允许只把某一类标记为已读。
 * 传空则全部 —— 这与前端的交互一致：「全部已读」按钮不区分类型，
 * 「只看订单消息」的页签里点已读就只清这一类。</p>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
@Data
@Schema(description = "全部已读入参")
public class MessageReadAllDTO {

    @Schema(description = "只标记某一类为已读，不传则全部", example = "ORDER_PROGRESS")
    private String type;
}
