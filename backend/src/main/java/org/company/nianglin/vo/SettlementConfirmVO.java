package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 单条结算核定结果（{@code docs/api/08-admin.md} §26）。
 *
 * <p>{@code settledNow} 是幂等语义的载体：</p>
 *
 * <ul>
 *   <li>{@code true} —— 本次调用真的把订单从未结算改成了已结算，并写了一条操作日志；</li>
 *   <li>{@code false} —— 该单之前就已是已结算，本次调用没有产生任何写操作，也没有新增日志。</li>
 * </ul>
 *
 * <p>两种情况的 HTTP 状态与业务码都是成功，客户端可安全重试。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-1
 */
@Data
@Accessors(chain = true)
@Schema(description = "结算核定结果")
public class SettlementConfirmVO {

    @Schema(description = "订单 ID", example = "1001")
    private Long orderId;

    @Schema(description = "订单号", example = "NL20260915000001")
    private String orderNo;

    @Schema(description = "结算状态枚举名", example = "SETTLED")
    private String paymentStatus;

    @Schema(description = "结算状态中文", example = "已结算")
    private String paymentStatusLabel;

    @Schema(description = "结算金额（服务端计算，两位小数字符串）", example = "120.00")
    private String settleAmount;

    @Schema(description = "本次调用是否真的完成了核定；false 表示此前已结算", example = "true")
    private Boolean settledNow;
}
