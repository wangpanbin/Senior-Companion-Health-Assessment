package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 干预效果对比（V3.6 · T5 · FR-Effect）。
 *
 * <h3>两侧同口径</h3>
 *
 * <p>{@code current} 与 {@code previous} 由<b>同一个</b>指标计算器算两遍
 * （决策 6.2）。如果两边各写一遍公式，对比出来的差值就没有意义 ——
 * 它可能只是两个公式的差异。</p>
 *
 * <p>指标名取自<b>指标字典</b>（{@code MetricDictionary}），不另写一份名表：
 * 两份名表迟早对不上，而对不上的那天没人知道哪个是对的。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Data
@Accessors(chain = true)
@Schema(description = "干预效果对比（两期同口径）")
public class OperationsEffectVO {

    @Schema(description = "指标 key", example = "operations.fulfillment_rate")
    private String metric;

    @Schema(description = "指标中文名（取自指标字典）", example = "履约率")
    private String metricName;

    @Schema(description = "后一期（被评估的区间）")
    private Period current;

    @Schema(description = "前一期（对比区间）")
    private Period previous;

    @Schema(description = "差值（带正负号）；两侧格式不同时为「—」", example = "+4.30%")
    private String delta;

    /**
     * 一期的取值。
     */
    @Data
    @Accessors(chain = true)
    @Schema(description = "一期的取值")
    public static class Period {

        @Schema(description = "区间开始日（含）", example = "2026-10-01")
        private String from;

        @Schema(description = "区间结束日（含）", example = "2026-10-15")
        private String to;

        @Schema(description = "该期的指标值（字符串形态，与驾驶舱同源）", example = "96.40%")
        private String value;
    }
}
