package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 陪诊员工作台趋势出参（V3.3 · FR-Trend）。
 *
 * <h3>为什么不复用 {@link ChartDataVO}</h3>
 *
 * <p>{@code ChartDataVO.Series#data} 是 {@code List<Integer>} —— 只能装计数。
 * 而本 VO 的收入趋势要装金额（{@code 3680.00}）、评分趋势要装两位小数，
 * 整数装不下。所以另起一个 {@code List<BigDecimal>} 的序列类型，
 * 而不是把金额塞进整数里丢掉小数。</p>
 *
 * <h3>长度一致性</h3>
 *
 * <p>与 {@code ChartDataVO} 同一条纪律：{@code categories} 与每个
 * {@code series.data} 的长度<b>必须严格一致</b>，无数据的桶补 0。
 * ECharts 对长度不齐的数据不报错，只是折线图静默跳段 —— 这种「假绿」最难查。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.3
 */
@Data
@Accessors(chain = true)
@Schema(description = "陪诊员工作台趋势（区间口径）")
public class CompanionTrendVO {

    @Schema(description = "趋势维度：ORDER-完成订单 / INCOME-收入 / RATING-评分", example = "ORDER")
    private String dimension;

    @Schema(description = "时间粒度：DAY / WEEK / MONTH", example = "DAY")
    private String granularity;

    @Schema(description = "统计区间开始日（含）", example = "2026-09-21")
    private String periodStart;

    @Schema(description = "统计区间结束日（含）", example = "2026-10-20")
    private String periodEnd;

    @Schema(description = "分类轴（桶起始日，升序）", example = "[\"2026-09-21\",\"2026-09-22\"]")
    private List<String> categories = new ArrayList<>();

    @Schema(description = "数据序列，每个序列的 data 长度与 categories 一致")
    private List<Series> series = new ArrayList<>();

    /** 一条数据序列 */
    @Data
    @Schema(description = "趋势数据序列")
    public static class Series {

        @Schema(description = "序列名（图例）", example = "完成订单")
        private String name;

        @Schema(description = "数据，长度与 categories 一致；无数据的桶为 0", example = "[3,0,5]")
        private List<BigDecimal> data = new ArrayList<>();
    }
}
