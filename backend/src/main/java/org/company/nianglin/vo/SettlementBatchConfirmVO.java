package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 批量核定汇总结果（{@code docs/api/08-admin.md} §27）。
 *
 * <p>汇总口径：{@code total = settledCount + alreadySettledCount + failedCount}。
 * 三个计数互相排斥，管理员据此判断「这次点下去到底处理了几单」。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-1
 */
@Data
@Accessors(chain = true)
@Schema(description = "批量核定结果")
public class SettlementBatchConfirmVO {

    @Schema(description = "去重后的订单总数", example = "3")
    private Integer total;

    @Schema(description = "本次真正核定的单数", example = "1")
    private Integer settledCount;

    @Schema(description = "此前已结算的单数（幂等命中）", example = "1")
    private Integer alreadySettledCount;

    @Schema(description = "失败的单数", example = "1")
    private Integer failedCount;

    @Schema(description = "逐项结果")
    private List<SettlementBatchItemVO> items;
}
