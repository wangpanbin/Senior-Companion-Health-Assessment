package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 统计接口通用入参（{@code docs/api/09-statistics-export.md} §一）。
 *
 * <p>时间区间默认最近 30 天、上限 366 天。默认值在 Service 里补齐而不是
 * 在这里给 {@code @Schema(defaultValue)} 了事 —— 注解只是文档，
 * 真正决定「不传时查什么区间」的必须是代码。</p>
 *
 * <p>日期不写 {@code @NotBlank}：全部统计接口都允许不传，
 * 而后端补默认值比让前端每次自己算「今天减 30 天」更可靠
 * （前端算的那份一定会有人写成 31 天，然后答辩时被追问口径）。</p>
 *
 * <p><b>V3.2 起继承 {@link DateRangeQuery}</b>：区间判定（默认 30 天 / 上限 366 天 /
 * 起止颠倒）只写一份，本模块与 V3.2 / V3.3 / V3.5 共用，
 * 避免同一个指标在不同角色端出现不同的可查区间。</p>
 *
 * @author 银龄伴诊团队
 * @since M10
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "统计接口通用入参")
public class StatisticsQuery extends DateRangeQuery {

    @Schema(description = "时间粒度：DAY（默认）/ WEEK / MONTH", example = "DAY")
    private String granularity;

    @Schema(description = "陪诊员排行返回条数，默认 10，最大 50", example = "10")
    @Min(value = 1, message = "返回条数不能小于 1")
    @Max(value = 50, message = "返回条数不能超过 50")
    private Integer limit;

    @Schema(description = "排行指标：ORDER_COUNT（默认）/ SCORE", example = "ORDER_COUNT")
    private String metric;

    @Schema(description = "漏服率统计时只看某位老人（老人档案 ID）", example = "401")
    private Long elderId;
}
