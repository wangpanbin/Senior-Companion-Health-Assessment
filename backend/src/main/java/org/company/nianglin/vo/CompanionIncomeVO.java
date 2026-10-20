package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 陪诊员收入中心出参（V3.3 · FR-Income）。
 *
 * <h3>为什么不出现「平台服务费」</h3>
 *
 * <p>迭代计划 §34 要求「订单金额 / 平台服务费 / 实际收入」三分，
 * 但现有费用模型里<b>根本没有「平台服务费」这个口径</b> ——
 * 只有 {@code fee}（合同服务费）、{@code actualFee}（结算金额）
 * 与费用项 {@code ADVANCE}（代垫）/ {@code SERVICE}（服务费）两型（ADR-0009）。</p>
 *
 * <p>roadmap 决策 3.6 明写「不引入新的金额字段」，所以本 VO 只做六项：
 * 订单金额 / 实际收入 / 待结算 / 已结算 / 代垫合计 / 服务费合计。</p>
 *
 * <p>⚠️ 指标口径已登记在指标字典（{@code companion.income.*}）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.3
 */
@Data
@Accessors(chain = true)
@Schema(description = "陪诊员收入中心（区间口径）")
public class CompanionIncomeVO {

    @Schema(description = "统计区间开始日（含）", example = "2026-09-21")
    private String periodStart;

    @Schema(description = "统计区间结束日（含）", example = "2026-10-20")
    private String periodEnd;

    @Schema(description = "区间订单金额（合同服务费合计）", example = "4000.00")
    private String orderAmount;

    @Schema(description = "区间实际收入（结算金额合计，未结算时回退合同服务费）", example = "3680.00")
    private String actualIncome;

    @Schema(description = "区间待结算金额", example = "1200.00")
    private String unsettledAmount;

    @Schema(description = "区间已结算金额", example = "2480.00")
    private String settledAmount;

    @Schema(description = "区间代垫合计（家属需还给陪诊员的部分）", example = "150.00")
    private String advanceTotal;

    @Schema(description = "区间服务费合计", example = "3650.00")
    private String serviceTotal;
}
