package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 费用明细汇总出参（ADR-0009）：明细 + 分组合计 + 兜底提示。
 *
 * @author 银龄伴诊团队
 * @since M4（收敛迭代 T2.5）
 */
@Data
@Schema(description = "订单费用明细汇总")
public class OrderFeeItemSummaryVO {

    @Schema(description = "明细列表，按发生时间升序")
    private List<OrderFeeItemVO> items;

    @Schema(description = "代垫合计（家属要还给陪诊员的部分）", example = "35.50")
    private String advanceTotal;

    @Schema(description = "服务费合计", example = "128.00")
    private String serviceTotal;

    @Schema(description = "总合计", example = "163.50")
    private String total;

    @Schema(description = "是否有明细记录")
    private Boolean hasItems;

    @Schema(description = "明细为空且已有申报金额时的兜底提示（ADR-0009：不静默伪造明细）",
            example = "该订单无明细记录")
    private String fallbackNotice;
}
