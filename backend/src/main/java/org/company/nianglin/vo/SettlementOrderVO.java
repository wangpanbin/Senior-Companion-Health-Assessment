package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 结算核对列表项（{@code docs/api/08-admin.md} §25）。
 *
 * <h3>金额用字符串而不是 {@code BigDecimal}</h3>
 *
 * <p>与 {@link OrderVO} 的 {@code fee} 保持同一口径：两位小数字符串。
 * JS 的 {@code Number} 表示 {@code 120.00} 会变成 {@code 120}，
 * 前端再格式化就多了出现浮点误差的机会，而对账场景下「差一分钱」是要被追问的。
 * {@code settleAmount} 由服务端算好后格式化成字符串，前端只负责展示。</p>
 *
 * <h3>姓名一律脱敏</h3>
 *
 * <p>本页是列表口径，订单详情里「全名可见」的豁免<b>不适用</b>于这里。
 * 服务端已用 {@code MaskUtil.name()} 处理，VO 里拿到的就是脱敏串。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-1
 */
@Data
@Accessors(chain = true)
@Schema(description = "结算核对列表项")
public class SettlementOrderVO {

    @Schema(description = "订单 ID", example = "1001")
    private Long id;

    @Schema(description = "订单号", example = "NL20260915000001")
    private String orderNo;

    @Schema(description = "家属姓名（脱敏）", example = "张*")
    private String familyName;

    @Schema(description = "老人姓名（脱敏）", example = "李*")
    private String elderName;

    @Schema(description = "陪诊员姓名（脱敏，未接单为空）", example = "王*")
    private String companionName;

    @Schema(description = "医院名称", example = "海南省人民医院")
    private String hospital;

    @Schema(description = "就诊科室", example = "心血管内科")
    private String department;

    @Schema(description = "就诊时间 yyyy-MM-dd HH:mm:ss", example = "2026-09-15 09:00:00")
    private String visitTime;

    @Schema(description = "服务完成时间 yyyy-MM-dd HH:mm:ss", example = "2026-09-15 12:30:00")
    private String finishTime;

    @Schema(description = "服务费（两位小数字符串）", example = "120.00")
    private String fee;

    @Schema(description = "结算金额（服务端计算，两位小数字符串）", example = "120.00")
    private String settleAmount;

    @Schema(description = "结算状态枚举名", example = "UNPAID")
    private String paymentStatus;

    @Schema(description = "结算状态中文", example = "未结算")
    private String paymentStatusLabel;

    @Schema(description = "订单状态枚举名", example = "COMPLETED")
    private String status;

    @Schema(description = "订单状态中文", example = "已完成")
    private String statusLabel;
}
