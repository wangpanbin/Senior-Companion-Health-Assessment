package org.company.nianglin.vo;

import org.company.nianglin.constant.VitalSource;
import org.company.nianglin.constant.VitalType;
import org.company.nianglin.entity.VitalSignRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * VitalSignVO 装配单测（V4 · P2-2）。
 *
 * <p>盯住三件事：</p>
 *
 * <ol>
 *   <li>三类体征的 {@code valueText} / {@code unit} 拼装正确，三端展示同一份文本；</li>
 *   <li>非本类型的数值字段保持 {@code null}，不把空值渲染成 0；</li>
 *   <li>来源与场景的中文标签由枚举给出，实体里不存展示文案。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-2
 */
@DisplayName("VitalSignVO：三类体征的展示文本与单位装配")
class VitalSignVOTest {

    @Test
    @DisplayName("血压：128/78 mmHg，单位 mmHg，其它数值字段为 null")
    void bloodPressureShouldRenderSystolicOverDiastolic() {
        VitalSignVO vo = VitalSignVO.of(bloodPressure(128, 78));

        assertEquals(VitalType.BLOOD_PRESSURE.getLabel(), vo.getVitalTypeLabel());
        assertEquals("128/78 mmHg", vo.getValueText());
        assertEquals("mmHg", vo.getUnit());
        assertEquals(128, vo.getSystolic());
        assertEquals(78, vo.getDiastolic());
        assertNull(vo.getGlucoseValue(), "血压记录不应带血糖值");
        assertNull(vo.getWeightKg(), "血压记录不应带体重");
        assertEquals(VitalSource.FAMILY_MANUAL.getLabel(), vo.getSourceTypeLabel());
    }

    @Test
    @DisplayName("血糖：6.2 mmol/L · 空腹，场景标签由枚举给出")
    void bloodGlucoseShouldAppendContextLabel() {
        VitalSignRecord record = base(VitalType.BLOOD_GLUCOSE);
        record.setGlucoseValue(new BigDecimal("6.2"));
        record.setGlucoseContext("FASTING");

        VitalSignVO vo = VitalSignVO.of(record);

        assertEquals("6.2 mmol/L · 空腹", vo.getValueText());
        assertEquals("mmol/L", vo.getUnit());
        assertEquals(new BigDecimal("6.2"), vo.getGlucoseValue());
        assertEquals("空腹", vo.getGlucoseContextLabel());
        assertNull(vo.getSystolic());
        assertNull(vo.getWeightKg());
    }

    @Test
    @DisplayName("体重：整数输入也补足 1 位小数，输出 62.0 kg")
    void weightShouldKeepOneDecimal() {
        VitalSignRecord record = base(VitalType.WEIGHT);
        record.setWeightKg(new BigDecimal("62"));

        VitalSignVO vo = VitalSignVO.of(record);

        assertEquals("62.0 kg", vo.getValueText());
        assertEquals("kg", vo.getUnit());
        assertNull(vo.getGlucoseValue());
        assertNull(vo.getSystolic());
    }

    @Test
    @DisplayName("null 实体安全：返回 null 而不是空 VO")
    void nullRecordShouldReturnNull() {
        assertNull(VitalSignVO.of(null));
    }

    private static VitalSignRecord base(VitalType type) {
        VitalSignRecord record = new VitalSignRecord();
        record.setId(1L);
        record.setElderId(301L);
        record.setVitalType(type.name());
        record.setMeasuredAt(LocalDateTime.of(2026, 11, 3, 8, 10));
        record.setSourceType(VitalSource.FAMILY_MANUAL.name());
        record.setOperatorUserId(11L);
        return record;
    }

    private static VitalSignRecord bloodPressure(int systolic, int diastolic) {
        VitalSignRecord record = base(VitalType.BLOOD_PRESSURE);
        record.setSystolic(systolic);
        record.setDiastolic(diastolic);
        return record;
    }
}
