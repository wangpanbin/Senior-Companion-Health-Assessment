package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 供给时段缺口分析入参（V3.6 · T4）。
 *
 * <p>继承 {@link DateRangeQuery} 复用跨版本的区间约定（默认最近 30 天 / 上限 366 天 /
 * 闭区间），另加「区间档位」开关。</p>
 *
 * <p>⚠️ {@code range = CUSTOM} 时才读 {@code startDate} / {@code endDate}；
 * 其余档位由服务端按「今天」推算。</p>
 *
 * <h3>为什么档位不叫「range」而趋势端点也叫「range」</h3>
 *
 * <p>两个端点用同名字段但语义一致（都是区间档位），不是巧合：
 * 前端传参时不需要为两个页面记两套词汇。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "供给时段缺口分析入参")
public class OperationsGapQuery extends DateRangeQuery {

    @Schema(description = "区间档位：LAST_7D-最近 7 天 / LAST_30D-最近 30 天（默认）/ "
            + "CUSTOM-自定义（此时读 startDate / endDate）", example = "LAST_30D")
    private String range;
}
