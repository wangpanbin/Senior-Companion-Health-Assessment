package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.entity.CompanionOrder;

import java.time.LocalDateTime;

/**
 * 接单结果。
 *
 * <p>对应 {@code POST /api/order/{id}/accept} 的响应（{@code docs/api/03-order.md} §6）。</p>
 *
 * <p>回 {@code acceptTime} 是有意的：接单是一个「时间点」事件，
 * 前端拿到后可以直接显示「您已于 17:02 接单」，不需要再发一次详情请求。
 * 服务端在这次写操作里本来就已经算出了这个时间，不回传等于让前端再问一遍。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
@Data
@Accessors(chain = true)
@Schema(description = "接单结果")
public class OrderAcceptResultVO {

    @Schema(description = "订单 ID", example = "1001")
    private Long orderId;

    @Schema(description = "订单状态枚举名（恒为 ACCEPTED）", example = "ACCEPTED")
    private String status;

    @Schema(description = "订单状态中文", example = "已接单")
    private String statusLabel;

    @Schema(description = "接单时间", example = "2026-09-15 17:02:11")
    private LocalDateTime acceptTime;

    public static OrderAcceptResultVO of(CompanionOrder order, LocalDateTime acceptTime) {
        if (order == null) {
            return null;
        }
        return new OrderAcceptResultVO()
                .setOrderId(order.getId())
                .setStatus(order.getStatus())
                .setStatusLabel(OrderStatus.labelOf(order.getStatus()))
                .setAcceptTime(acceptTime);
    }
}
