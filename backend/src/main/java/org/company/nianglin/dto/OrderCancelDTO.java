package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 取消订单入参。
 *
 * <p>对应 {@code PUT /api/order/{id}/cancel}（{@code docs/api/03-order.md} §5）。</p>
 *
 * <p>取消原因<b>必填</b>：这是唯一一处家属可以单方面终止订单的入口，
 * 原因会写进取消记录并出现在双方可见的时间线里。留空的话，
 * 陪诊员只看到「订单被取消了」却不知道为什么，纠纷处理时也没有依据。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
@Data
@Schema(description = "取消订单入参")
public class OrderCancelDTO {

    @Schema(description = "取消原因", example = "老人临时身体不适，改天再去")
    @NotBlank(message = "请填写取消原因")
    @Size(max = 200, message = "取消原因不能超过 200 个字符")
    private String reason;
}
