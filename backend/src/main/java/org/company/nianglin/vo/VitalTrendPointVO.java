package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.GlucoseContext;
import org.company.nianglin.entity.VitalSignRecord;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 体征趋势单点（V4 · P2-2）。
 *
 * <p>只携带该次测量的数值与时间；<b>不带任何区间、比较或判断字段</b>。
 * 折线图由前端按数值原样绘制，不做阈值着色。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-2
 */
@Data
@Accessors(chain = true)
@Schema(description = "体征趋势单点")
public class VitalTrendPointVO {

    @Schema(description = "测量时间", example = "2026-10-06 08:10:00")
    private LocalDateTime measuredAt;

    @Schema(description = "展示文本", example = "126/76 mmHg")
    private String valueText;

    @Schema(description = "收缩压（mmHg），仅血压返回", example = "126")
    private Integer systolic;

    @Schema(description = "舒张压（mmHg），仅血压返回", example = "76")
    private Integer diastolic;

    @Schema(description = "血糖值（mmol/L），仅血糖返回", example = "6.2")
    private BigDecimal glucoseValue;

    @Schema(description = "血糖场景：FASTING / POSTPRANDIAL / RANDOM，仅血糖返回", example = "FASTING")
    private String glucoseContext;

    @Schema(description = "血糖场景展示名", example = "空腹")
    private String glucoseContextLabel;

    @Schema(description = "体重（kg），仅体重返回", example = "62.5")
    private BigDecimal weightKg;

    /**
     * 实体 → 趋势单点。
     *
     * @param record 记录实体，可为 {@code null}
     * @return VO；入参为 {@code null} 时返回 {@code null}
     */
    public static VitalTrendPointVO of(VitalSignRecord record) {
        if (record == null) {
            return null;
        }
        return new VitalTrendPointVO()
                .setMeasuredAt(record.getMeasuredAt())
                .setValueText(VitalSignVO.valueTextOf(record))
                .setSystolic(record.getSystolic())
                .setDiastolic(record.getDiastolic())
                .setGlucoseValue(record.getGlucoseValue())
                .setGlucoseContext(record.getGlucoseContext())
                .setGlucoseContextLabel(GlucoseContext.labelOf(record.getGlucoseContext()))
                .setWeightKg(record.getWeightKg());
    }
}
