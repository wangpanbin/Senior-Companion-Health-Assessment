package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 图表数据（分类轴 + 多条序列）。
 *
 * <p>对应 {@code docs/api/09-statistics-export.md} §2（订单趋势）与 §5（漏服率）。</p>
 *
 * <h3>本类存在的唯一理由是「长度必须一致」</h3>
 *
 * <p>验收项写得很直白：{@code categories} 与每个 {@code series[].data}
 * 长度必须完全一致，且无数据的日期要补 0。这条约束<b>只能由同一段代码保证</b> ——
 * 如果让每个统计方法各自拼装两个数组，早晚会有一个方法忘了补零，
 * 而 ECharts 对这种数据的表现是「折线图静默跳段」，不报错，只是图不对。</p>
 *
 * <p>因此构造统一走 {@link #of}：分类轴先算出来，每条序列再按同一个
 * 日期轴逐个取值，缺的补 0。序列多一条、日期轴多一天都不会破坏对齐。</p>
 *
 * @author 银龄伴诊团队
 * @since M10
 */
@Data
@Accessors(chain = true)
@Schema(description = "图表数据")
public class ChartDataVO {

    @Schema(description = "时间粒度：DAY / WEEK / MONTH", example = "DAY")
    private String granularity;

    @Schema(description = "分类轴（日期或周/月的起点，升序）",
            example = "[\"2026-09-13\",\"2026-09-14\",\"2026-09-15\"]")
    private List<String> categories = new ArrayList<>();

    @Schema(description = "数据序列，每个序列的 data 长度与 categories 一致")
    private List<Series> series = new ArrayList<>();

    /** 一条数据序列 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "数据序列")
    public static class Series {

        @Schema(description = "序列名（图例）", example = "新增订单")
        private String name;

        @Schema(description = "数据，长度与 categories 一致", example = "[12,18,15]")
        private List<Integer> data = new ArrayList<>();
    }

    public static ChartDataVO of(String granularity, List<String> categories, List<Series> series) {
        return new ChartDataVO()
                .setGranularity(granularity)
                .setCategories(categories == null ? new ArrayList<>() : categories)
                .setSeries(series == null ? new ArrayList<>() : series);
    }

    /**
     * 比率转百分数字符串，两位小数。
     *
     * <p>分母为 0 时返回 {@code "0.00%"} 而不是 {@code "NaN%"} 或 {@code null}：
     * 新区间内没有任何订单是<b>正常状态</b>，接口不该让前端为它写特例
     * （与 {@code docs/api/09} §一「空数据返回空数组或 0，不返回 null，不报错」一致）。</p>
     */
    public static String percent(long numerator, long denominator) {
        if (denominator <= 0) {
            return "0.00%";
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP)
                .toPlainString() + "%";
    }
}
