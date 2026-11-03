package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 体征趋势查询入参（V4 · P2-2）。
 *
 * <p>趋势图一次只画一种体征，因此 {@code vitalType} 必填；区间口径同
 * {@link DateRangeQuery}（默认 30 天 / 上限 366 天 / 闭区间）。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-2
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "体征趋势查询入参")
public class VitalTrendQuery extends DateRangeQuery {

    @NotBlank(message = "体征类型不能为空")
    @Size(max = 24, message = "体征类型取值不合法")
    @Schema(description = "体征类型：BLOOD_PRESSURE / BLOOD_GLUCOSE / WEIGHT",
            example = "BLOOD_PRESSURE", requiredMode = Schema.RequiredMode.REQUIRED)
    private String vitalType;
}
