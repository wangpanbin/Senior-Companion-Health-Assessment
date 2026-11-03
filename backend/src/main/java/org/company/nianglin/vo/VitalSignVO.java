package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.GlucoseContext;
import org.company.nianglin.constant.VitalSource;
import org.company.nianglin.constant.VitalType;
import org.company.nianglin.entity.VitalSignRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 体征记录（V4 · P2-2）。
 *
 * <h3>只陈述事实</h3>
 *
 * <p>本 VO 只包含「哪一类、什么时间、多少数值、谁录的」。
 * <b>不返回</b>录入人姓名 / 手机号 / 身份证号，<b>不返回</b>任何区间、分级或解释性字段
 * （合规红线-1）。</p>
 *
 * <p>{@code valueText} 由服务端拼装，三端展示一致，前端不重复拼字符串。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-2
 */
@Data
@Accessors(chain = true)
@Schema(description = "体征记录")
public class VitalSignVO {

    @Schema(description = "记录 ID", example = "1")
    private Long id;

    @Schema(description = "老人档案 ID", example = "301")
    private Long elderId;

    @Schema(description = "体征类型：BLOOD_PRESSURE / BLOOD_GLUCOSE / WEIGHT", example = "BLOOD_PRESSURE")
    private String vitalType;

    @Schema(description = "体征类型展示名", example = "血压")
    private String vitalTypeLabel;

    @Schema(description = "测量时间", example = "2026-11-03 08:10:00")
    private LocalDateTime measuredAt;

    @Schema(description = "展示文本", example = "128/78 mmHg")
    private String valueText;

    @Schema(description = "展示单位", example = "mmHg")
    private String unit;

    @Schema(description = "收缩压（mmHg），仅血压返回", example = "128")
    private Integer systolic;

    @Schema(description = "舒张压（mmHg），仅血压返回", example = "78")
    private Integer diastolic;

    @Schema(description = "血糖值（mmol/L），仅血糖返回", example = "6.2")
    private BigDecimal glucoseValue;

    @Schema(description = "血糖场景：FASTING / POSTPRANDIAL / RANDOM，仅血糖返回", example = "FASTING")
    private String glucoseContext;

    @Schema(description = "血糖场景展示名", example = "空腹")
    private String glucoseContextLabel;

    @Schema(description = "体重（kg），仅体重返回", example = "62.5")
    private BigDecimal weightKg;

    @Schema(description = "录入来源：FAMILY_MANUAL / COMPANION_SERVICE", example = "FAMILY_MANUAL")
    private String sourceType;

    @Schema(description = "录入来源展示名", example = "家属代录")
    private String sourceTypeLabel;

    @Schema(description = "陪诊订单 ID；仅陪诊员代录时返回", example = "1001")
    private Long orderId;

    /**
     * 实体 → VO。
     *
     * @param record 记录实体，可为 {@code null}
     * @return VO；入参为 {@code null} 时返回 {@code null}
     */
    public static VitalSignVO of(VitalSignRecord record) {
        if (record == null) {
            return null;
        }
        VitalType type = VitalType.of(record.getVitalType());
        return new VitalSignVO()
                .setId(record.getId())
                .setElderId(record.getElderId())
                .setVitalType(record.getVitalType())
                .setVitalTypeLabel(type == null ? null : type.getLabel())
                .setMeasuredAt(record.getMeasuredAt())
                .setValueText(valueTextOf(record))
                .setUnit(type == null ? null : type.getUnit())
                .setSystolic(record.getSystolic())
                .setDiastolic(record.getDiastolic())
                .setGlucoseValue(record.getGlucoseValue())
                .setGlucoseContext(record.getGlucoseContext())
                .setGlucoseContextLabel(GlucoseContext.labelOf(record.getGlucoseContext()))
                .setWeightKg(record.getWeightKg())
                .setSourceType(record.getSourceType())
                .setSourceTypeLabel(VitalSource.labelOf(record.getSourceType()))
                .setOrderId(record.getOrderId());
    }

    /**
     * 拼装展示文本。
     *
     * <p>三类各有固定形态：血压 {@code 128/78 mmHg}、血糖 {@code 6.2 mmol/L · 空腹}、
     * 体重 {@code 62.5 kg}。拼装只做字符串连接，不做任何比较或判断。</p>
     *
     * @param record 记录实体
     * @return 展示文本；字段缺失时返回 {@code null}
     */
    public static String valueTextOf(VitalSignRecord record) {
        VitalType type = VitalType.of(record.getVitalType());
        if (type == null) {
            return null;
        }
        switch (type) {
            case BLOOD_PRESSURE -> {
                if (record.getSystolic() == null || record.getDiastolic() == null) {
                    return null;
                }
                return record.getSystolic() + "/" + record.getDiastolic() + " " + type.getUnit();
            }
            case BLOOD_GLUCOSE -> {
                if (record.getGlucoseValue() == null) {
                    return null;
                }
                String text = oneDecimal(record.getGlucoseValue()) + " " + type.getUnit();
                String contextLabel = GlucoseContext.labelOf(record.getGlucoseContext());
                return contextLabel == null ? text : text + " · " + contextLabel;
            }
            case WEIGHT -> {
                if (record.getWeightKg() == null) {
                    return null;
                }
                return oneDecimal(record.getWeightKg()) + " " + type.getUnit();
            }
            default -> {
                return null;
            }
        }
    }

    /** 保留 1 位小数并输出纯文本（避免科学计数法） */
    private static String oneDecimal(BigDecimal value) {
        return value.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}
