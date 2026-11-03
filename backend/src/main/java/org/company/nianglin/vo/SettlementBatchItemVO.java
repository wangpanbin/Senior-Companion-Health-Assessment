package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 批量核定的单条结果（{@code docs/api/08-admin.md} §27）。
 *
 * <p>逐项返回而不是「整批成功 / 整批失败」：管理员一次勾选多单，
 * 其中一两单不满足前置条件时，其余单不应该跟着白做。</p>
 *
 * <p>{@code success=true} 包含「本次真正核定」与「此前已结算」两种，
 * 后者由 {@code settledNow=false} 区分；{@code success=false} 时
 * {@code code} 是具体错误码（如 {@code 8004} / {@code 3001}）。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-1
 */
@Data
@Accessors(chain = true)
@Schema(description = "批量核定单条结果")
public class SettlementBatchItemVO {

    @Schema(description = "订单 ID", example = "1001")
    private Long orderId;

    @Schema(description = "订单号；订单不存在时为空", example = "NL20260915000001")
    private String orderNo;

    @Schema(description = "本项是否成功（含此前已结算）", example = "true")
    private Boolean success;

    @Schema(description = "本次调用是否真的完成了核定", example = "true")
    private Boolean settledNow;

    @Schema(description = "业务码；成功为 200", example = "200")
    private Integer code;

    @Schema(description = "结果说明", example = "已核定")
    private String message;

    @Schema(description = "结算金额（服务端计算，两位小数字符串）；失败或订单不存在时为空", example = "120.00")
    private String settleAmount;
}
