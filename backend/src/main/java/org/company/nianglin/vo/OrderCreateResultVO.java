package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.entity.CompanionOrder;

/**
 * 下单结果。
 *
 * <p>对应 {@code POST /api/order} 的响应（{@code docs/api/03-order.md} §1）。</p>
 *
 * <p>只回 4 个字段，不回整个订单：前端下单成功后要做的是「把订单号告诉用户，
 * 并跳转到订单详情」，多余的字段只会让前端误以为这里有它需要的数据。
 * 也刻意<b>不回接单陪诊员信息</b> —— 刚下单时本来就没有人接单，
 * 返回一个 {@code null} 的字段不如让它直接不存在。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
@Data
@Accessors(chain = true)
@Schema(description = "下单结果")
public class OrderCreateResultVO {

    @Schema(description = "订单 ID", example = "1001")
    private Long orderId;

    @Schema(description = "订单号", example = "NL20260915000001")
    private String orderNo;

    @Schema(description = "订单状态枚举名（恒为 PENDING）", example = "PENDING")
    private String status;

    @Schema(description = "订单状态中文", example = "待接单")
    private String statusLabel;

    public static OrderCreateResultVO of(CompanionOrder order) {
        if (order == null) {
            return null;
        }
        return new OrderCreateResultVO()
                .setOrderId(order.getId())
                .setOrderNo(order.getOrderNo())
                .setStatus(order.getStatus())
                .setStatusLabel(OrderStatus.labelOf(order.getStatus()));
    }
}
