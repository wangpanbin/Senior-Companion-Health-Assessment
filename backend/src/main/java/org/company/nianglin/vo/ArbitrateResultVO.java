package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.OrderStatus;

import java.time.LocalDateTime;

/**
 * 纠纷处理结果（{@code docs/api/08-admin.md} §9）。
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@Accessors(chain = true)
@Schema(description = "纠纷处理结果")
public class ArbitrateResultVO {

    @Schema(description = "订单 ID", example = "1001")
    private Long orderId;

    @Schema(description = "强制进入的终态：COMPLETED / CANCELLED", example = "CANCELLED")
    private String status;

    @Schema(description = "状态中文名", example = "已取消")
    private String statusLabel;

    @Schema(description = "处理时间", example = "2026-09-21 10:00:00")
    private LocalDateTime handleTime;

    public static ArbitrateResultVO of(Long orderId, OrderStatus status, LocalDateTime handleTime) {
        return new ArbitrateResultVO()
                .setOrderId(orderId)
                .setStatus(status == null ? null : status.name())
                .setStatusLabel(status == null ? null : status.getLabel())
                .setHandleTime(handleTime);
    }
}
