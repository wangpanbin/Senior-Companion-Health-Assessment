package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.OrderStatus;

/**
 * 订单状态分布项（{@code docs/api/09-statistics-export.md} §3）。
 *
 * <p>六种状态<b>全部返回</b>，数量为 0 的也返回 —— 验收项明确写了这一条，
 * 原因是饼图的图例是按数据项渲染的：某天恰好没有「已取消」的单，
 * 图例就会少一块颜色，看起来像是系统丢了状态定义。</p>
 *
 * @author 银龄伴诊团队
 * @since M10
 */
@Data
@Accessors(chain = true)
@Schema(description = "订单状态分布项")
public class OrderStatusStatVO {

    @Schema(description = "状态枚举名", example = "COMPLETED")
    private String status;

    @Schema(description = "状态中文名", example = "已完成")
    private String statusLabel;

    @Schema(description = "数量（为 0 时也返回）", example = "96")
    private Integer count;

    @Schema(description = "占比（两位小数百分数）", example = "70.07%")
    private String percent;

    public static OrderStatusStatVO of(OrderStatus status, long count, long total) {
        return new OrderStatusStatVO()
                .setStatus(status.name())
                .setStatusLabel(status.getLabel())
                .setCount((int) count)
                .setPercent(ChartDataVO.percent(count, total));
    }
}
