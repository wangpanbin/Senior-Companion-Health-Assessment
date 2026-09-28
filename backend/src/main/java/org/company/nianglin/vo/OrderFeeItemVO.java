package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.company.nianglin.entity.OrderFeeItem;

import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 费用明细出参（ADR-0009）。金额为字符串两位小数（AGENTS.md §4.4）。
 *
 * @author 银龄伴诊团队
 * @since M4（收敛迭代 T2.5）
 */
@Data
@Schema(description = "费用明细条目")
public class OrderFeeItemVO {

    @Schema(description = "明细 ID")
    private Long id;

    @Schema(description = "费用类型：ADVANCE / SERVICE")
    private String itemType;

    @Schema(description = "费用类型中文：代垫 / 服务费")
    private String itemTypeLabel;

    @Schema(description = "项目名")
    private String itemName;

    @Schema(description = "金额（元），字符串两位小数", example = "35.50")
    private String amount;

    @Schema(description = "费用发生时间")
    private LocalDateTime occurredAt;

    public static OrderFeeItemVO of(OrderFeeItem item) {
        OrderFeeItemVO vo = new OrderFeeItemVO();
        vo.setId(item.getId());
        vo.setItemType(item.getItemType());
        vo.setItemTypeLabel("ADVANCE".equals(item.getItemType()) ? "代垫" : "服务费");
        vo.setItemName(item.getItemName());
        vo.setAmount(item.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString());
        vo.setOccurredAt(item.getOccurredAt());
        return vo;
    }
}
