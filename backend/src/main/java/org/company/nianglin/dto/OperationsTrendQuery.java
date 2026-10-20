package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 平台运营驾驶舱趋势入参（V3.5 · T3）。
 *
 * <p>继承 {@link DateRangeQuery} 复用跨版本的区间约定（默认最近 30 天 / 上限 366 天 /
 * 闭区间），另加「维度」与「区间档位」两个开关。</p>
 *
 * <p>⚠️ {@code range = CUSTOM} 时才读 {@code startDate} / {@code endDate}；
 * 其余档位由服务端按「今天」推算，避免同一天里前端算的「今天」和服务端算的「今天」
 * 落在两个时区边界上。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.5
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "平台运营驾驶舱趋势入参")
public class OperationsTrendQuery extends DateRangeQuery {

    @Schema(description = "趋势维度：ORDER-订单量（默认）/ COMPANION_ACTIVE-活跃陪诊员数 / "
            + "FULFILLMENT_RATE-履约率 / COMPLAINT-投诉订单数 / REVIEW-评价条数 / MEDICATION-用药任务数",
            example = "ORDER")
    private String dimension;

    @Schema(description = "区间档位：TODAY-今天 / LAST_7D-最近 7 天 / LAST_30D-最近 30 天（默认）/ "
            + "CUSTOM-自定义（此时读 startDate / endDate）", example = "LAST_7D")
    private String range;
}
