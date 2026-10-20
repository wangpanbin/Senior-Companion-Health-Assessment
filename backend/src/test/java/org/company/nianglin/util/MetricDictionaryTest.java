package org.company.nianglin.util;

import org.company.nianglin.vo.ElderHealthOverviewVO;
import org.company.nianglin.vo.FamilyHealthSummaryVO;
import org.company.nianglin.vo.HealthTimelineVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标字典的双向校验（V3.2 · T-0 · FR-Metric）。
 *
 * <h3>这个测试在防什么</h3>
 *
 * <p>指标口径漂移最常见的路径是「先在某个 VO 里加了个字段，回头忘了登记口径」——
 * 于是这个数字只有实现的人知道它是怎么算的，家属端和管理端各自理解成不同的东西。</p>
 *
 * <p>本测试做两件事，缺一不可：</p>
 * <ol>
 *   <li><b>字典 → VO</b>：字典里声明的每个绑定，VO 里必须真的有这个字段
 *       （防止「字典写了，代码没实现」）；</li>
 *   <li><b>VO → 字典</b>：VO 里的每个字段，要么是已登记的指标，
 *       要么在 {@code vos} 段被声明为结构字段
 *       （防止「代码加了，字典没登记」）。</li>
 * </ol>
 *
 * <p>再加上「每条指标必须写全五要素」——
 * 决策 6.1 要求的「指标名称 / 统计范围 / 统计时间 / 数据来源 / 计算公式」
 * 缺一项就不算登记过。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@DisplayName("指标字典：五要素齐全 / 字典与 VO 双向一致")
class MetricDictionaryTest {

    private final MetricDictionary dictionary = MetricDictionary.load();

    /* ================================================================== */
    /* 1 · 五要素                                                          */
    /* ================================================================== */

    @Test
    @DisplayName("字典可加载，且至少登记了一条指标")
    void dictionaryShouldLoad() {
        assertTrue(dictionary.version() > 0, "字典必须有版本号");
        assertFalse(dictionary.metrics().isEmpty(), "字典不能是空的");
    }

    @Test
    @DisplayName("每条指标都写全五要素：名称 / 范围 / 时间 / 来源 / 公式")
    void everyMetricShouldDeclareAllFiveParts() {
        for (MetricDictionary.Metric metric : dictionary.metrics()) {
            assertNotNull(metric.key(), "指标必须有 key");
            assertNotNull(metric.name(), metric.key() + " 缺少指标名称");
            assertNotNull(metric.scope(), metric.key() + " 缺少统计范围");
            assertNotNull(metric.period(), metric.key() + " 缺少统计时间");
            assertNotNull(metric.source(), metric.key() + " 缺少数据来源");
            assertNotNull(metric.formula(), metric.key() + " 缺少计算公式");
            assertFalse(metric.name().isBlank(), metric.key() + " 的指标名称不能为空");
            assertFalse(metric.scope().isBlank(), metric.key() + " 的统计范围不能为空");
            assertFalse(metric.period().isBlank(), metric.key() + " 的统计时间不能为空");
            assertFalse(metric.source().isBlank(), metric.key() + " 的数据来源不能为空");
            assertFalse(metric.formula().isBlank(), metric.key() + " 的计算公式不能为空");
            assertTrue(metric.caliberVersion() >= 1, metric.key() + " 的口径版本号必须从 1 起");
        }
    }

    @Test
    @DisplayName("指标 key 不重复")
    void metricKeysShouldBeUnique() {
        Set<String> keys = new HashSet<>();
        for (MetricDictionary.Metric metric : dictionary.metrics()) {
            assertTrue(keys.add(metric.key()), "指标 key 重复：" + metric.key());
        }
    }

    /* ================================================================== */
    /* 2 · 字典 → VO                                                       */
    /* ================================================================== */

    @Test
    @DisplayName("字典里声明的每个绑定，VO 里必须真的有这个字段")
    void everyBindingShouldExistInItsVo() {
        for (MetricDictionary.Metric metric : dictionary.metrics()) {
            for (MetricDictionary.Binding binding : metric.bindings()) {
                Class<?> vo = resolve(binding.vo());
                assertNotNull(vo, "字典引用了不存在的 VO：" + binding.vo());
                assertNotNull(findField(vo, binding.field()),
                        metric.key() + " 绑定的字段不存在：" + binding.vo() + "#" + binding.field());
            }
        }
    }

    /* ================================================================== */
    /* 3 · VO → 字典                                                       */
    /* ================================================================== */

    @Test
    @DisplayName("VO 的每个字段要么已登记为指标，要么被声明为结构字段")
    void everyVoFieldShouldBeEitherMetricOrStructural() {
        // 指标字段全集
        Set<String> metricFields = new HashSet<>();
        for (MetricDictionary.Metric metric : dictionary.metrics()) {
            for (MetricDictionary.Binding binding : metric.bindings()) {
                metricFields.add(binding.vo() + "#" + binding.field());
            }
        }

        for (MetricDictionary.VoSpec spec : dictionary.vos()) {
            Class<?> vo = resolve(spec.className());
            assertNotNull(vo, "字典声明了不存在的 VO：" + spec.className());

            Set<String> structural = new LinkedHashSet<>(spec.structural());
            for (Field field : vo.getDeclaredFields()) {
                if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String path = spec.className() + "#" + field.getName();
                assertTrue(metricFields.contains(path) || structural.contains(field.getName()),
                        path + " 既没有登记为指标，也没有声明为结构字段 —— "
                                + "新加的指标必须先进指标字典");
            }
        }
    }

    @Test
    @DisplayName("指标字典覆盖了 V3.2 的三个核心 VO")
    void dictionaryShouldCoverCoreV3_2Vos() {
        Set<String> declared = new HashSet<>();
        for (MetricDictionary.VoSpec spec : dictionary.vos()) {
            declared.add(spec.className());
        }
        assertTrue(declared.contains(ElderHealthOverviewVO.class.getSimpleName()));
        assertTrue(declared.contains(FamilyHealthSummaryVO.class.getSimpleName()));
        assertTrue(declared.contains(HealthTimelineVO.class.getSimpleName()));
    }

    @Test
    @DisplayName("结构字段声明里不能有字典中不存在的字段（防写错字段名）")
    void structuralDeclarationsShouldAllExist() {
        for (MetricDictionary.VoSpec spec : dictionary.vos()) {
            Class<?> vo = resolve(spec.className());
            assertNotNull(vo, "字典声明了不存在的 VO：" + spec.className());
            for (String name : spec.structural()) {
                assertNotNull(findField(vo, name),
                        spec.className() + " 的结构字段声明里写了不存在的字段：" + name);
            }
        }
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    private Class<?> resolve(String simpleName) {
        try {
            return Class.forName(MetricDictionary.VO_PACKAGE + simpleName);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private Field findField(Class<?> type, String name) {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            chain.add(current);
        }
        for (Class<?> current : chain) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续往父类找
            }
        }
        return null;
    }
}
