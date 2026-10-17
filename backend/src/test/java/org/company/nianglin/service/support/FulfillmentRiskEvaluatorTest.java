package org.company.nianglin.service.support;

import org.company.nianglin.constant.FulfillmentRisk;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 履约风险判定单测（V3.1 · T1）。
 *
 * <p>这是 V3.1 唯一的纯函数测试面，也是风险等级的<b>唯一真相表</b>。
 * 它刻意不加载 Spring 上下文、不碰数据库、不 mock 时钟 —— 跑一次是毫秒级，
 * 因此可以把「每个阈值的前一格 / 恰好 / 后一格」全部覆盖，而不必挑选几条凑数。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@DisplayName("履约风险判定：阈值边界 / 单调性 / 配置自洽")
class FulfillmentRiskEvaluatorTest {

    /** 测试固定阈值：预警 10 / 紧急 30 / 人工 120，与 application.yml 缺省值一致 */
    private static final int WARNING = 10;
    private static final int URGENT = 30;
    private static final int MANUAL = 120;

    /* ================================================================== */
    /* 阈值边界（表驱动）                                                 */
    /* ================================================================== */

    @Nested
    @DisplayName("阈值边界")
    class Boundary {

        @ParameterizedTest(name = "等待 {0} 分钟 → {1}")
        @CsvSource({
                // 负值与零：脏数据防御
                "-1,      NORMAL",
                "-999,    NORMAL",
                "0,       NORMAL",
                // 预警
                "9,       NORMAL",
                "10,      WARNING",   // 恰好等于阈值即升档
                "29,      WARNING",
                // 紧急
                "30,      URGENT",
                "119,     URGENT",
                // 人工
                "120,     MANUAL_REQUIRED",
                "100000,  MANUAL_REQUIRED",
        })
        @DisplayName("每个阈值的前一格 / 恰好 / 后一格都覆盖")
        void evaluateShouldReturnExpectedRisk(int waitMinutes, FulfillmentRisk expected) {
            assertEquals(expected,
                    FulfillmentRiskEvaluator.evaluate(waitMinutes, WARNING, URGENT, MANUAL));
        }
    }

    /* ================================================================== */
    /* 不变量                                                             */
    /* ================================================================== */

    @Test
    @DisplayName("等待时长递增时风险等级永不下降（单调性）")
    void riskShouldNeverDecreaseAsWaitGrows() {
        FulfillmentRisk previous = FulfillmentRisk.NORMAL;
        for (int wait = 0; wait <= MANUAL + 10; wait++) {
            FulfillmentRisk current = FulfillmentRiskEvaluator.evaluate(wait, WARNING, URGENT, MANUAL);
            assertTrue(current.atLeast(previous),
                    "等待 " + wait + " 分钟时等级从 " + previous + " 降到了 " + current);
            previous = current;
        }
    }

    @Test
    @DisplayName("负等待分钟数按最低等级处理且不抛异常")
    void negativeWaitShouldFallBackToNormal() {
        assertEquals(FulfillmentRisk.NORMAL,
                FulfillmentRiskEvaluator.evaluate(-1, WARNING, URGENT, MANUAL));
        assertEquals(FulfillmentRisk.NORMAL,
                FulfillmentRiskEvaluator.evaluate(Integer.MIN_VALUE, WARNING, URGENT, MANUAL));
    }

    @Test
    @DisplayName("判定不修改入参、不持有状态：同一输入永远同一输出")
    void evaluateShouldBePure() {
        for (int i = 0; i < 3; i++) {
            assertEquals(FulfillmentRisk.URGENT,
                    FulfillmentRiskEvaluator.evaluate(45, WARNING, URGENT, MANUAL));
        }
    }

    /* ================================================================== */
    /* 阈值可配置：同样的等待时长，不同阈值必须给出不同结论              */
    /* ================================================================== */

    @Test
    @DisplayName("阈值全部可配置：同一订单在不同阈值下给出不同结论")
    void thresholdsShouldBeConfigurable() {
        int wait = 45;
        // 缺省阈值 10 / 30 / 120：等了 45 分钟 → 超过紧急线但未到人工线
        assertEquals(FulfillmentRisk.URGENT,
                FulfillmentRiskEvaluator.evaluate(wait, 10, 30, 120));
        // 阈值调紧到 10 / 25 / 40：同一笔单直接判定为需要人工介入
        assertEquals(FulfillmentRisk.MANUAL_REQUIRED,
                FulfillmentRiskEvaluator.evaluate(wait, 10, 25, 40));
        // 阈值调松到 60 / 90 / 120：同一笔单还只是正常等待
        assertEquals(FulfillmentRisk.NORMAL,
                FulfillmentRiskEvaluator.evaluate(wait, 60, 90, 120));
    }

    /* ================================================================== */
    /* 配置自洽校验                                                       */
    /* ================================================================== */

    @Test
    @DisplayName("合法阈值组合通过校验")
    void validateThresholdsShouldAcceptOrderedThresholds() {
        FulfillmentRiskEvaluator.validateThresholds(WARNING, URGENT, MANUAL);
    }

    @ParameterizedTest(name = "阈值 [{0},{1},{2}] 应被拒绝")
    @CsvSource({
                "0,   30,  120",   // 预警非正
                "-5,  30,  120",
                "30,  30,  120",   // 紧急 == 预警
                "40,  30,  120",   // 紧急 < 预警（反向配置，最危险的一种）
                "10,  30,  30",    // 人工 == 紧急
                "10,  40,  30",    // 人工 < 紧急
                "10,  30,  20",
    })
    @DisplayName("阈值顺序不自洽时启动失败（不静默生效）")
    void validateThresholdsShouldRejectUnorderedThresholds(int warning, int urgent, int manual) {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> FulfillmentRiskEvaluator.validateThresholds(warning, urgent, manual));
        assertTrue(ex.getMessage().contains("nianglin.fulfillment"),
                "异常消息应指明是哪个配置项出了问题，实际：" + ex.getMessage());
    }

    /* ================================================================== */
    /* 风险等级枚举本身                                                   */
    /* ================================================================== */

    @Test
    @DisplayName("等级严重度顺序为 NORMAL < WARNING < URGENT < MANUAL_REQUIRED")
    void severityShouldBeOrdered() {
        assertTrue(FulfillmentRisk.NORMAL.atLeast(FulfillmentRisk.NORMAL));
        assertTrue(FulfillmentRisk.WARNING.atLeast(FulfillmentRisk.NORMAL));
        assertTrue(FulfillmentRisk.URGENT.atLeast(FulfillmentRisk.WARNING));
        assertTrue(FulfillmentRisk.MANUAL_REQUIRED.atLeast(FulfillmentRisk.URGENT));

        assertFalse(FulfillmentRisk.NORMAL.atLeast(FulfillmentRisk.WARNING));
        assertFalse(FulfillmentRisk.WARNING.atLeast(FulfillmentRisk.URGENT));
        assertFalse(FulfillmentRisk.URGENT.atLeast(FulfillmentRisk.MANUAL_REQUIRED));
        assertFalse(FulfillmentRisk.NORMAL.atLeast(null));
    }

    @Test
    @DisplayName("只有 NORMAL 未升高")
    void onlyNormalIsNotRaised() {
        assertFalse(FulfillmentRisk.NORMAL.isRaised());
        assertTrue(FulfillmentRisk.WARNING.isRaised());
        assertTrue(FulfillmentRisk.URGENT.isRaised());
        assertTrue(FulfillmentRisk.MANUAL_REQUIRED.isRaised());
    }

    @Test
    @DisplayName("labelOf 对非法值原样返回（脏数据不该让整页 500）")
    void labelOfShouldPassThroughIllegalValue() {
        assertEquals("正常等待", FulfillmentRisk.labelOf("NORMAL"));
        assertEquals("需要人工介入", FulfillmentRisk.labelOf("manual_required"));
        assertEquals("AT_RISK", FulfillmentRisk.labelOf("AT_RISK"));
        assertEquals(null, FulfillmentRisk.labelOf(null));
    }

    @Test
    @DisplayName("of 对非法值返回 null，交给调用方决定怎么报错")
    void ofShouldReturnNullForIllegalValue() {
        assertEquals(FulfillmentRisk.URGENT, FulfillmentRisk.of("urgent"));
        assertEquals(null, FulfillmentRisk.of("AT_RISK"));
        assertEquals(null, FulfillmentRisk.of(null));
    }

    @ValueSource(strings = {"NORMAL", "WARNING", "URGENT", "MANUAL_REQUIRED"})
    @DisplayName("四个等级都有非空中文标签")
    void everyRiskShouldHaveLabel(String name) {
        FulfillmentRisk risk = FulfillmentRisk.of(name);
        assertTrue(risk != null && risk.getLabel() != null && !risk.getLabel().isEmpty());
    }
}
