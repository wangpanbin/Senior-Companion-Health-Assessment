package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 订单纠纷处理入参（{@code docs/api/08-admin.md} §9）。
 *
 * <h3>只允许指定终态，不允许指定任意状态</h3>
 *
 * <p>{@code targetStatus} 只接受 {@code COMPLETED} 与 {@code CANCELLED}
 * （代码层由 {@code OrderStatus.isAdminForceable()} 兜住）。
 * 若允许管理员把订单改成 {@code IN_SERVICE} 这类中间态，
 * 状态机会被拉回一个「服务中」的订单上，而陪诊员与家属对「谁该继续做」
 * 没有任何共识 —— 强制终态的意义就在于「一次性结束争议」。</p>
 *
 * <h3>退费是记账，不是退款</h3>
 *
 * <p>{@code refundToFamily} 只影响结算标记（一期不做在线支付，
 * 见计划书「合规与隐私说明」），平台不会真的发起任何资金操作。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@Schema(description = "订单纠纷处理入参")
public class ArbitrateDTO {

    @Schema(description = "强制进入的终态：COMPLETED / CANCELLED", example = "CANCELLED",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请指定要强制进入的终态")
    private String targetStatus;

    @Schema(description = "处理结果说明，10–500 字符",
            example = "经核实陪诊员迟到 40 分钟且未提前告知，本次订单取消，服务费不结算。",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请填写处理结果说明")
    @Size(min = 10, max = 500, message = "处理结果说明长度应为 10–500 个字符")
    private String result;

    @Schema(description = "是否退费给家属（线上记账标记，线下结算，不做在线退款）", example = "true")
    private Boolean refundToFamily;

    @Schema(description = "是否对陪诊员计违规", example = "true")
    private Boolean penaltyToCompanion;
}
