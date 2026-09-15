package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 漏服率统计（{@code docs/api/09-statistics-export.md} §5）。
 *
 * <p>{@code chart} 是「按时间粒度的三条序列」，{@code summary} 是区间汇总。
 * 两者放在同一个响应里而不是拆成两个接口：前端是一个「图 + 卡」的页面，
 * 拆成两个接口就有两次往返，而且两次查询之间数据可能已经变了 ——
 * 图表与汇总卡数字对不上，是最容易被截图当成 bug 的那种问题。</p>
 *
 * @author 银龄伴诊团队
 * @since M10
 */
@Data
@Accessors(chain = true)
@Schema(description = "漏服率统计")
public class MedicationMissedVO {

    @Schema(description = "图表数据（序列：任务总数 / 已服用 / 漏服）")
    private ChartDataVO chart;

    @Schema(description = "区间汇总")
    private Summary summary;

    /** 区间汇总 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "漏服率区间汇总")
    public static class Summary {

        @Schema(description = "任务总数", example = "150")
        private Integer taskTotalCount;

        @Schema(description = "已服用", example = "139")
        private Integer takenCount;

        @Schema(description = "漏服数（{@code was_missed = 1}，含漏服后补记）", example = "11")
        private Integer missedCount;

        @Schema(description = "漏服率", example = "7.33%")
        private String missedRate;
    }

    public static MedicationMissedVO of(ChartDataVO chart, long total, long taken, long missed) {
        return new MedicationMissedVO()
                .setChart(chart)
                .setSummary(new Summary()
                        .setTaskTotalCount((int) total)
                        .setTakenCount((int) taken)
                        .setMissedCount((int) missed)
                        .setMissedRate(ChartDataVO.percent(missed, total)));
    }
}
