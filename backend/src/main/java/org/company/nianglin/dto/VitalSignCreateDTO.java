package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 体征录入入参（V4 · P2-2）。
 *
 * <h3>为什么字段是「三种类型共用一套」</h3>
 *
 * <p>三类体征共用一张表与一套时间线，入参也共用一份：按 {@code vitalType}
 * 只取用本类型的数值字段，其余字段必须为空（互斥校验在 Service 内完成，
 * 因为「哪些字段必填」依赖 {@code vitalType}）。</p>
 *
 * <p>⚠️ <b>刻意不接受</b> {@code elderId}、{@code sourceType}、{@code operatorUserId}、
 * {@code orderId}：老人由路径 / 订单推导，来源与录入人由登录态写入 ——
 * 让前端决定这些值等于把归属校验交给调用方。</p>
 *
 * <p>⚠️ 本入参<b>没有备注字段</b>：不提供任何可以写判断性描述的入口。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-2
 */
@Data
@Schema(description = "体征录入入参")
public class VitalSignCreateDTO {

    @NotBlank(message = "体征类型不能为空")
    @Size(max = 24, message = "体征类型取值不合法")
    @Schema(description = "体征类型：BLOOD_PRESSURE / BLOOD_GLUCOSE / WEIGHT", example = "BLOOD_PRESSURE")
    private String vitalType;

    @NotNull(message = "测量时间不能为空")
    @Schema(description = "测量时间（分钟精度）", example = "2026-11-03 08:10:00")
    private LocalDateTime measuredAt;

    @Schema(description = "收缩压（mmHg），仅血压时填写", example = "128")
    private Integer systolic;

    @Schema(description = "舒张压（mmHg），仅血压时填写", example = "78")
    private Integer diastolic;

    @Schema(description = "血糖值（mmol/L），仅血糖时填写", example = "6.2")
    private BigDecimal glucoseValue;

    @Schema(description = "血糖场景：FASTING / POSTPRANDIAL / RANDOM，仅血糖时填写", example = "FASTING")
    private String glucoseContext;

    @Schema(description = "体重（kg），仅体重时填写", example = "62.5")
    private BigDecimal weightKg;
}
