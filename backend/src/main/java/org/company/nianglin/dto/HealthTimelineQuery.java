package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 健康时间线查询入参（V3.2 · FR-Timeline）。
 *
 * <p>区间部分继承 {@link DateRangeQuery}，与 M10 统计共用同一套默认与上限
 * （默认 30 天 / 上限 366 天 / 闭区间）—— 同一个指标在不同页面有不同的可查区间，
 * 是「口径分裂」最常见的表现形式。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "健康时间线查询入参")
public class HealthTimelineQuery extends DateRangeQuery {

    @Schema(description = "事件分类过滤，逗号分隔：ORDER / CHECKIN / MEDICATION / MESSAGE；不传取全部",
            example = "ORDER,MEDICATION")
    private String categories;

    @Schema(description = "单页条数，默认 50，最大 200", example = "50")
    @Min(value = 1, message = "单页条数不能小于 1")
    @Max(value = 200, message = "单页条数不能超过 200")
    private Integer limit;

    @Schema(description = "游标，取上一页返回的 nextCursor；首页不传", example = "MjAyNi0xMC0yMFQxMDowMDowMHwxMjM=")
    private String cursor;
}
