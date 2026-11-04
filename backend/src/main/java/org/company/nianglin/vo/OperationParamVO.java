package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 运营参数列表项（{@code GET /api/admin/operation-param}）。
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-3
 */
@Data
@Accessors(chain = true)
@Schema(description = "运营参数")
public class OperationParamVO {

    @Schema(description = "字典键", example = "medication.missed-threshold-minutes")
    private String code;

    @Schema(description = "中文名", example = "漏服判定阈值")
    private String label;

    @Schema(description = "当前生效值", example = "60")
    private Integer value;

    @Schema(description = "application.yml 默认值", example = "60")
    private Integer defaultValue;

    @Schema(description = "允许的最小值", example = "1")
    private Integer min;

    @Schema(description = "允许的最大值", example = "1440")
    private Integer max;

    @Schema(description = "单位", example = "分钟")
    private String unit;

    @Schema(description = "参数说明", example = "服药计划时间超过该分钟数仍未确认即判定漏服")
    private String description;

    @Schema(description = "字典状态：0-停用 1-启用；无字典行时为 null", example = "1")
    private Integer status;

    @Schema(description = "是否已被字典覆盖（与默认值不同）", example = "false")
    private Boolean overridden;

    @Schema(description = "是否正在使用默认值（字典缺失、停用或读库失败）", example = "false")
    private Boolean usingDefault;

    @Schema(description = "字典行最近更新时间")
    private LocalDateTime updateTime;
}
