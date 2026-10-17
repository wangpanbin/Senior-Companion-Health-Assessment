package org.company.nianglin.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 履约保障配置的启动期校验单测（V3.1 · T1）。
 *
 * <p>这组测试保护的是一条产品级不变量：<b>错误配置必须在启动时就失败</b>。
 * 若阈值顺序不检查，一个写反的配置能正常启动，表现为「订单先变紧急、
 * 很久之后才变预警」——线上排查这种问题通常要花掉半天。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@DisplayName("履约保障配置：启动期自洽校验")
class FulfillmentPropertiesTest {

    private FulfillmentProperties props(int warning, int urgent, int manual,
                                        int cooldown, int maxTimes) {
        FulfillmentProperties p = new FulfillmentProperties();
        p.setWarningMinutes(warning);
        p.setUrgentMinutes(urgent);
        p.setManualRequiredMinutes(manual);
        p.setUrgeCooldownMinutes(cooldown);
        p.setUrgeMaxTimes(maxTimes);
        return p;
    }

    @Test
    @DisplayName("缺省值（10 / 30 / 120）通过校验")
    void defaultsShouldBeValid() {
        assertDoesNotThrow(() -> new FulfillmentProperties().validate());
    }

    @Test
    @DisplayName("合法自定义阈值通过校验")
    void validCustomThresholdsShouldPass() {
        assertDoesNotThrow(() -> props(5, 15, 60, 3, 5).validate());
        // 允许阈值相等以外的紧邻值，只要求严格递增
        assertDoesNotThrow(() -> props(1, 2, 3, 1, 1).validate());
    }

    @Test
    @DisplayName("紧急阈值等于预警阈值时启动失败")
    void equalThresholdsShouldFailFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> props(30, 30, 120, 10, 3).validate());
        assertTrue(ex.getMessage().contains("urgent-minutes"), ex.getMessage());
    }

    @Test
    @DisplayName("紧急阈值小于预警阈值（反向配置）时启动失败")
    void reversedThresholdsShouldFailFast() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> props(30, 10, 120, 10, 3).validate());
        assertTrue(ex.getMessage().contains("warning-minutes"), ex.getMessage());
    }

    @Test
    @DisplayName("人工介入阈值不大于紧急阈值时启动失败")
    void manualRequiredNotGreaterShouldFailFast() {
        assertThrows(IllegalStateException.class, () -> props(10, 30, 30, 10, 3).validate());
        assertThrows(IllegalStateException.class, () -> props(10, 30, 20, 10, 3).validate());
    }

    @Test
    @DisplayName("催单冷却期或次数上限非正时启动失败")
    void invalidUrgeLimitsShouldFailFast() {
        assertThrows(IllegalStateException.class, () -> props(10, 30, 120, 0, 3).validate());
        assertThrows(IllegalStateException.class, () -> props(10, 30, 120, -1, 3).validate());
        assertThrows(IllegalStateException.class, () -> props(10, 30, 120, 10, 0).validate());
        assertThrows(IllegalStateException.class, () -> props(10, 30, 120, 10, -2).validate());
    }
}
