package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 拒单入参。
 *
 * <p>对应 {@code POST /api/order/{id}/reject}（{@code docs/api/03-order.md} §7）。</p>
 *
 * <p>拒单<b>不改变订单状态</b>，订单仍留在大厅等其他陪诊员，只是不会再出现在
 * 这位陪诊员的大厅列表里。原因必填，用于后续统计与风控 ——
 * 如果某个陪诊员对大量订单都以「没时间」拒单，运营能看出来。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
@Data
@Schema(description = "拒单入参")
public class OrderRejectDTO {

    @Schema(description = "拒单原因", example = "当天已有其他订单，时间冲突")
    @NotBlank(message = "请填写拒单原因")
    @Size(max = 200, message = "拒单原因不能超过 200 个字符")
    private String reason;
}
