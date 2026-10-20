package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 陪诊员工作台趋势入参（V3.3 · FR-Trend）。
 *
 * <p>继承 {@link DateRangeQuery} 复用跨版本的区间约定（默认最近 30 天 / 上限 366 天 /
 * 闭区间），另加两个维度开关。</p>
 *
 * <p>⚠️ <b>不接收 {@code companionId}</b> —— 陪诊员身份从登录态取，
 * 少一个可以出错的地方（与 V3.2 老人端同一条理由）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.3
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "陪诊员工作台趋势入参")
public class CompanionTrendQuery extends DateRangeQuery {

    @Schema(description = "趋势维度：ORDER-完成订单（默认）/ INCOME-收入 / RATING-评分", example = "ORDER")
    private String dimension;

    @Schema(description = "时间粒度：DAY（默认）/ WEEK / MONTH", example = "DAY")
    private String granularity;
}
