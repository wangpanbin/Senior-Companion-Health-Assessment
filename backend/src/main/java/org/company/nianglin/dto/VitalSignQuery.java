package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 体征时间线查询入参（V4 · P2-2）。
 *
 * <p>区间沿用 {@link DateRangeQuery}（默认 30 天 / 上限 366 天 / 闭区间），
 * 分页沿用 {@code PageQuery} 的约定（默认 10 条 / 上限 100 条），
 * 不另立一套口径。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-2
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "体征时间线查询入参")
public class VitalSignQuery extends DateRangeQuery {

    @Size(max = 24, message = "体征类型取值不合法")
    @Schema(description = "体征类型过滤：BLOOD_PRESSURE / BLOOD_GLUCOSE / WEIGHT；不传取全部",
            example = "BLOOD_PRESSURE")
    private String vitalType;

    @Min(value = 1, message = "页码不能小于 1")
    @Schema(description = "页码，从 1 开始", example = "1", defaultValue = "1")
    private Long page = 1L;

    @Min(value = 1, message = "每页条数不能小于 1")
    @Max(value = 100, message = "每页条数不能超过 100")
    @Schema(description = "每页条数，最大 100", example = "10", defaultValue = "10")
    private Long size = 10L;
}
