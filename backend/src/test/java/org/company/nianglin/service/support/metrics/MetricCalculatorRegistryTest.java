package org.company.nianglin.service.support.metrics;

import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.mapper.OperationsMapper;
import org.company.nianglin.util.MetricDictionary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标计算器注册表的校验（V3.6 · T3 · T7）。
 *
 * <h3>这个测试在防什么</h3>
 *
 * <ol>
 *   <li><b>一个 key 两个实现</b> —— 注册表必须在启动时就拒绝，
 *       而不是「后注册的覆盖先注册的」（那会让口径取决于 Bean 的加载顺序）；</li>
 *   <li><b>注册了一个字典里没有的指标</b> —— 指标必须先登记再出现；</li>
 *   <li><b>漏注册一个可算指标</b> —— 效果对比端点会对它返回 400。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@DisplayName("V3.6 指标计算器注册表：key 唯一 / 与字典一致 / 未注册即拒绝")
class MetricCalculatorRegistryTest {

    /** 指标字典（机器面） */
    private final MetricDictionary dictionary = MetricDictionary.load();

    /** 全部真实计算器（用 mock mapper 实例化 —— 注册表只关心 key，不关心取数） */
    private final List<MetricCalculator> realCalculators = List.of(
            new FulfillmentRateCalculator(mockMapper(), new OperationsProperties()),
            new CompletionRateCalculator(mockMapper(), new OperationsProperties()),
            new ComplaintRateCalculator(mockMapper(), new OperationsProperties()),
            new AverageScoreCalculator(mockMapper(), new OperationsProperties()),
            new AvgAcceptMinutesCalculator(mockMapper(), new OperationsProperties()),
            new ActiveCompanionCountCalculator(mockMapper(), new OperationsProperties()),
            new CompletedOrderCountCalculator(mockMapper(), new OperationsProperties()),
            new TimeoutRateCalculator(mockMapper(), new OperationsProperties()),
            new ManualInterventionRateCalculator(mockMapper(), new OperationsProperties()),
            new ReassignRateCalculator(mockMapper(), new OperationsProperties()),
            new ServiceDurationCalculator(mockMapper(), new OperationsProperties()),
            new MedicationTaskCountCalculator(mockMapper(), new OperationsProperties()),
            new MedicationMissedRateCalculator(mockMapper(), new OperationsProperties()));

    /**
     * 效果对比端点必须支持的指标（工单 §四 + §七 补登的那些）。
     */
    private static final List<String> REQUIRED_KEYS = List.of(
            "operations.fulfillment_rate",
            "operations.completion_rate",
            "operations.complaint_rate",
            "operations.average_score",
            "operations.avg_accept_minutes",
            "operations.active_companion_count",
            "operations.completed_order_count",
            "companion.workbench.completion_rate",
            "operations.timeout_rate",
            "operations.manual_intervention_rate",
            "operations.reassign_rate",
            "operations.service_duration_minutes",
            "operations.companion_count",
            "operations.medication_task_count",
            "operations.medication_missed_rate");

    /* ================================================================== */
    /* 1 · key 唯一                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("同一个 key 被两个计算器声明 → 注册表构造即失败（启动失败）")
    void duplicateKeyShouldFailFast() {
        MetricCalculator first = fixedCalculator("operations.fulfillment_rate", "96.00%");
        MetricCalculator second = fixedCalculator("operations.fulfillment_rate", "88.00%");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> new MetricCalculatorRegistry(List.of(first, second)));

        assertTrue(ex.getMessage().contains("operations.fulfillment_rate"),
                "异常信息里必须写清楚是哪个 key 重复了：" + ex.getMessage());
    }

    @Test
    @DisplayName("别名 key 与别人的主 key 撞了 → 同样启动失败")
    void duplicateAliasKeyShouldFailFast() {
        MetricCalculator first = fixedCalculator("operations.fulfillment_rate", "96.00%");
        MetricCalculator second = new MetricCalculator() {
            @Override
            public String metricKey() {
                return "operations.other_metric";
            }

            @Override
            public String calculate(org.company.nianglin.common.DateRange range) {
                return "1";
            }

            @Override
            public List<String> aliasKeys() {
                return List.of("operations.fulfillment_rate");
            }
        };

        assertThrows(IllegalStateException.class,
                () -> new MetricCalculatorRegistry(List.of(first, second)));
    }

    @Test
    @DisplayName("全部真实计算器的 key 两两不重复（复制粘贴多写一个 key 会被抓住）")
    void realCalculatorKeysShouldBeUnique() {
        MetricCalculatorRegistry registry = new MetricCalculatorRegistry(realCalculators);

        Set<String> keys = registry.keys();
        List<String> all = new ArrayList<>();
        for (MetricCalculator calculator : realCalculators) {
            all.add(calculator.metricKey());
            all.addAll(calculator.aliasKeys());
        }
        assertEquals(all.size(), keys.size(), "有计算器声明了重复的 key：" + all);
    }

    /* ================================================================== */
    /* 2 · 与字典一致                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("注册表里的每个 key 都在指标字典里登记过")
    void everyRegisteredKeyShouldExistInDictionary() {
        MetricCalculatorRegistry registry = new MetricCalculatorRegistry(realCalculators);

        for (String key : registry.keys()) {
            assertNotNull(dictionary.find(key), "注册的指标 " + key + " 没有在指标字典里登记");
        }
    }

    @Test
    @DisplayName("工单要求的 15 个指标全部已注册（少一个 → 效果对比会 400）")
    void allRequiredMetricsShouldBeRegistered() {
        MetricCalculatorRegistry registry = new MetricCalculatorRegistry(realCalculators);

        for (String key : REQUIRED_KEYS) {
            assertNotNull(registry.find(key), "指标 " + key + " 没有注册计算器");
        }
    }

    @Test
    @DisplayName("「平台履约率」与「陪诊员区间完成率」共用同一个计算器（决策 6.2）")
    void sameCaliberShouldShareOneCalculator() {
        MetricCalculatorRegistry registry = new MetricCalculatorRegistry(realCalculators);

        MetricCalculator fulfillment = registry.find("operations.fulfillment_rate");
        MetricCalculator companionCompletion = registry.find("companion.workbench.completion_rate");

        assertNotNull(fulfillment);
        assertNotNull(companionCompletion);
        assertEquals(fulfillment, companionCompletion,
                "两个指标的公式逐字相同，必须共用一个计算器 —— 各写一份迟早会漂移");
    }

    @Test
    @DisplayName("「活跃陪诊员数」与「区间陪诊员数」共用同一个计算器（公式相同）")
    void companionCountShouldShareOneCalculator() {
        MetricCalculatorRegistry registry = new MetricCalculatorRegistry(realCalculators);

        assertEquals(registry.find("operations.active_companion_count"),
                registry.find("operations.companion_count"),
                "两个指标的公式逐字相同，必须共用一个计算器");
    }

    /* ================================================================== */
    /* 3 · 未注册的指标被拒                                                */
    /* ================================================================== */

    @Test
    @DisplayName("未注册的 key 查不到（效果对比端点据此返回 400，不猜算法）")
    void unknownKeyShouldNotBeFound() {
        MetricCalculatorRegistry registry = new MetricCalculatorRegistry(realCalculators);

        assertNull(registry.find("operations.not_registered_metric"));
        assertNull(registry.find(null));
        assertNull(registry.find("  "));
        assertFalse(registry.supports("operations.not_registered_metric"));
    }

    @Test
    @DisplayName("字典里登记但没有计算器的指标查不到（如陪诊员画像的累计完成率）")
    void dictionaryOnlyMetricShouldNotBeFound() {
        MetricCalculatorRegistry registry = new MetricCalculatorRegistry(realCalculators);

        // 字典里有这条指标，但它不属于「平台级可计算指标」，所以注册表里没有
        assertNotNull(dictionary.find("companion.profile.total_completion_rate"));
        assertNull(registry.find("companion.profile.total_completion_rate"));
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    private static OperationsMapper mockMapper() {
        return Mockito.mock(OperationsMapper.class);
    }

    /** 造一个固定返回值的计算器（只关心 key 的场景） */
    private static MetricCalculator fixedCalculator(String key, String value) {
        return new MetricCalculator() {
            @Override
            public String metricKey() {
                return key;
            }

            @Override
            public String calculate(org.company.nianglin.common.DateRange range) {
                return value;
            }
        };
    }
}
