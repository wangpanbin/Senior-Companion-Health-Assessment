package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 平台运营驾驶舱趋势（V3.5 · T3）。
 *
 * <h3>长度一致性</h3>
 *
 * <p>与 {@code ChartDataVO} / {@code CompanionTrendVO} 同一条纪律：
 * {@code categories} 与每个 {@code series.data} 的长度<b>必须严格一致</b>，
 * 无数据的桶补 0。ECharts 对长度不齐的数据不报错，只是折线图静默跳段 ——
 * 这种「假绿」最难查。</p>
 *
 * <p>与 {@code CompanionTrendVO} 一样用 {@code List<BigDecimal>} 而不是
 * {@code List<Integer>}：履约率是百分比（96.40），整数装不下。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.5
 */
@Data
@Accessors(chain = true)
@Schema(description = "平台运营驾驶舱趋势")
public class OperationsTrendVO {

    @Schema(description = "趋势维度：ORDER / COMPANION_ACTIVE / FULFILLMENT_RATE / COMPLAINT / REVIEW / MEDICATION",
            example = "ORDER")
    private String dimension;

    @Schema(description = "区间档位：TODAY / LAST_7D / LAST_30D / CUSTOM", example = "LAST_7D")
    private String range;

    @Schema(description = "时间粒度：驾驶舱固定 DAY", example = "DAY")
    private String granularity;

    @Schema(description = "统计区间开始日（含）", example = "2026-10-14")
    private String periodStart;

    @Schema(description = "统计区间结束日（含）", example = "2026-10-20")
    private String periodEnd;

    @Schema(description = "分类轴（日期，升序）", example = "[\"2026-10-14\",\"2026-10-15\"]")
    private List<String> categories = new ArrayList<>();

    @Schema(description = "数据序列，每个序列的 data 长度与 categories 一致")
    private List<Series> series = new ArrayList<>();

    /** 一条数据序列 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "趋势数据序列")
    public static class Series {

        @Schema(description = "序列名（图例）", example = "订单量")
        private String name;

        @Schema(description = "数据，长度与 categories 一致；无数据的桶为 0", example = "[12.00,9.00]")
        private List<BigDecimal> data = new ArrayList<>();
    }
}
