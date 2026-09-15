package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.PaymentStatus;

import java.time.LocalDateTime;

/**
 * 订单状态流转结果。
 *
 * <p>同时服务于「开始服务」（§8）与「完成服务」（§9）两个接口。</p>
 *
 * <h3>为什么两个接口共用一个 VO</h3>
 *
 * <p>两者的响应形状本来就只差一个字段：开始服务回 {@code startTime}，
 * 完成服务回 {@code finishTime} + {@code paymentStatus}。若拆成两个类，
 * 将来加一个「服务时长」之类的字段就要改两处，而漏改一处不会有任何编译错误 ——
 * 那种缺陷只会在前端发现「这个接口少返回一个字段」时才暴露。</p>
 *
 * <p>空字段靠全局 Jackson {@code non_null} 策略自动消失，因此前端看到的
 * 依然是两个干净的、字段各不相同的响应。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
@Data
@Accessors(chain = true)
@Schema(description = "订单状态流转结果")
public class OrderFlowResultVO {

    @Schema(description = "流转后的状态枚举名", example = "IN_SERVICE")
    private String status;

    @Schema(description = "流转后的状态中文", example = "服务中")
    private String statusLabel;

    @Schema(description = "开始服务时间；仅开始服务返回", example = "2026-09-20 09:10:00")
    private LocalDateTime startTime;

    @Schema(description = "完成时间；仅完成服务返回", example = "2026-09-20 12:05:00")
    private LocalDateTime finishTime;

    @Schema(description = "结算状态枚举名；仅完成服务返回", example = "UNPAID")
    private String paymentStatus;

    @Schema(description = "结算状态中文；仅完成服务返回", example = "未结算")
    private String paymentStatusLabel;

    /** 开始服务 */
    public static OrderFlowResultVO ofStarted(LocalDateTime startTime) {
        return new OrderFlowResultVO()
                .setStatus(OrderStatus.IN_SERVICE.name())
                .setStatusLabel(OrderStatus.IN_SERVICE.getLabel())
                .setStartTime(startTime);
    }

    /**
     * 完成服务。
     *
     * <p>不回「已完成即已结算」：一期走线下结算，完成时结算状态恒为
     * {@code paymentStatus} 参数传进来的真实值（通常是 {@code UNPAID}）。
     * 把它写死反而会在 M9 支持线下回填后变成一句谎话。</p>
     */
    public static OrderFlowResultVO ofCompleted(LocalDateTime finishTime, String paymentStatus) {
        return new OrderFlowResultVO()
                .setStatus(OrderStatus.COMPLETED.name())
                .setStatusLabel(OrderStatus.COMPLETED.getLabel())
                .setFinishTime(finishTime)
                .setPaymentStatus(paymentStatus)
                .setPaymentStatusLabel(PaymentStatus.labelOf(paymentStatus));
    }
}
