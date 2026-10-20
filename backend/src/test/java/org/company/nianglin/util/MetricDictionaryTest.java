package org.company.nianglin.util;

import org.company.nianglin.vo.ElderHealthOverviewVO;
import org.company.nianglin.vo.FamilyHealthSummaryVO;
import org.company.nianglin.vo.HealthTimelineVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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
 * <p>本测试做三件事，缺一不可：</p>
 * <ol>
 *   <li><b>字典 → VO</b>：字典里声明的每个绑定，VO 里必须真的有这个字段
 *       （防止「字典写了，代码没实现」）；</li>
 *   <li><b>VO → 字典</b>：VO 里的每个字段，要么是已登记的指标，
 *       要么在 {@code vos} 段被声明为结构字段
 *       （防止「代码加了，字典没登记」）；</li>
 *   <li><b>可达性</b>：被健康接口暴露出来的 VO（含泛型参数里的）
 *       <b>必须</b>出现在字典里 —— 这一条堵住了「新增一个 VO 直接用，没人登记就溜过去」，
 *       因为 {@code vos} 段本身就是人工维护的清单，光靠它自己守不住自己。</li>
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
    /* 4 · 可达性：被接口暴露的 VO 必须登记                                   */
    /* ================================================================== */

    @Test
    @DisplayName("被健康接口暴露出来的 VO（含泛型参数）必须登记在字典里")
    void everyReachableVoShouldBeRegistered() {
        Set<String> declared = new HashSet<>();
        for (MetricDictionary.VoSpec spec : dictionary.vos()) {
            declared.add(spec.className());
        }

        Deque<String> queue = new ArrayDeque<>(declared);
        Set<String> visited = new HashSet<>();
        while (!queue.isEmpty()) {
            String name = queue.poll();
            if (!visited.add(name)) {
                continue;
            }
            Class<?> vo = resolve(name);
            if (vo == null) {
                continue;
            }
            for (Field field : vo.getDeclaredFields()) {
                for (Class<?> referenced : referencedVoTypes(field)) {
                    String key = simpleNameOf(referenced);
                    assertTrue(declared.contains(key),
                            "VO " + name + " 的字段 " + field.getName()
                                    + " 暴露了未登记的 VO " + key
                                    + " —— 它会随接口一起返回，口径必须登记");
                    queue.add(key);
                }
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
    @DisplayName("指标字典覆盖了 V3.3 陪诊员工作台的六个 VO")
    void dictionaryShouldCoverV3_3CompanionWorkbenchVos() {
        Set<String> declared = new HashSet<>();
        for (MetricDictionary.VoSpec spec : dictionary.vos()) {
            declared.add(spec.className());
        }
        // 概览 / 趋势（含嵌套 Series）/ 画像 / 擅长标签 / 收入中心
        assertTrue(declared.contains("CompanionWorkbenchOverviewVO"));
        assertTrue(declared.contains("CompanionTrendVO"));
        assertTrue(declared.contains("CompanionTrendVO$Series"));
        assertTrue(declared.contains("CompanionServiceProfileVO"));
        assertTrue(declared.contains("CompanionExpertiseVO"));
        assertTrue(declared.contains("CompanionIncomeVO"));
    }

    @Test
    @DisplayName("V3.3 的每个工作台端点都在字典里登记过（不新定义，只登记）")
    void v3_3EndpointsShouldAllBeRegistered() {
        Set<String> registered = new HashSet<>();
        for (MetricDictionary.Metric metric : dictionary.metrics()) {
            registered.addAll(metric.endpoints());
        }
        for (String endpoint : List.of(
                "GET /api/statistics/companion/me",
                "GET /api/statistics/companion/me/trend",
                "GET /api/statistics/companion/me/profile",
                "GET /api/statistics/companion/me/income")) {
            assertTrue(registered.contains(endpoint),
                    "端点 " + endpoint + " 没有在指标字典里登记 —— 新指标必须先进字典");
        }
    }

    @Test
    @DisplayName("同一指标的多个出口必须写同一个公式（决策 6.2：只有一个口径）")
    void sameIndicatorShouldShareOneFormula() {
        // 平均评分在概览与画像两个出口出现，公式必须一致
        MetricDictionary.Metric score = dictionary.find("companion.average_score");
        assertNotNull(score, "平均评分必须在字典里登记");
        assertTrue(score.bindings().size() >= 2,
                "平均评分是同一个指标的两个出口（概览 / 画像），应登记在同一条指标下");
    }

    /* ================================================================== */
    /* 5 · V3.5 平台运营驾驶舱                                              */
    /* ================================================================== */

    @Test
    @DisplayName("指标字典覆盖了 V3.5 驾驶舱的五个 VO 及其嵌套类")
    void dictionaryShouldCoverV3_5OperationsVos() {
        Set<String> declared = new HashSet<>();
        for (MetricDictionary.VoSpec spec : dictionary.vos()) {
            declared.add(spec.className());
        }
        assertTrue(declared.contains("OperationsOverviewVO"));
        assertTrue(declared.contains("OperationsExceptionVO"));
        assertTrue(declared.contains("OperationsExceptionItemVO"));
        assertTrue(declared.contains("OperationsTrendVO"));
        assertTrue(declared.contains("OperationsTrendVO$Series"));
        assertTrue(declared.contains("OperationsHealthVO"));
    }

    @Test
    @DisplayName("V3.5 的四个驾驶舱端点都在字典里登记过")
    void v3_5EndpointsShouldAllBeRegistered() {
        Set<String> registered = new HashSet<>();
        for (MetricDictionary.Metric metric : dictionary.metrics()) {
            registered.addAll(metric.endpoints());
        }
        for (String endpoint : List.of(
                "GET /api/admin/operations/overview",
                "GET /api/admin/operations/exceptions",
                "GET /api/admin/operations/trend",
                "GET /api/admin/operations/health")) {
            assertTrue(registered.contains(endpoint),
                    "端点 " + endpoint + " 没有在指标字典里登记 —— 新指标必须先进字典");
        }
    }

    @Test
    @DisplayName("V3.5 的运营指标全部登记（14 条核心指标 + 趋势序列值）")
    void v3_5ShouldRegisterAllFourteenOperationsMetrics() {
        for (String key : List.of(
                "operations.today_order_count",
                "operations.completed_order_count",
                "operations.in_service_count",
                "operations.pending_count",
                "operations.fulfillment_rate",
                "operations.completion_rate",
                "operations.avg_accept_minutes",
                "operations.complaint_rate",
                "operations.average_score",
                "operations.active_companion_count",
                "operations.exception_manual_required_count",
                "operations.exception_match_warning_count",
                "operations.exception_complaint_rate_count",
                "operations.exception_medication_missed_count",
                // 与 companion.trend.series_value 同范式：趋势序列本身也是指标，
                // 否则趋势端点会在字典里留下一个没有口径的出口
                "operations.trend.series_value")) {
            assertNotNull(dictionary.find(key), "指标 " + key + " 没有在字典里登记");
        }
    }

    @Test
    @DisplayName("「履约率」与「完成率（按时）」必须是两个不同指标（偏差 D-3.5-1）")
    void fulfillmentRateAndCompletionRateShouldBeTwoDifferentMetrics() {
        MetricDictionary.Metric fulfillment = dictionary.find("operations.fulfillment_rate");
        MetricDictionary.Metric completion = dictionary.find("operations.completion_rate");
        assertNotNull(fulfillment, "履约率必须在字典里登记");
        assertNotNull(completion, "完成率（按时）必须在字典里登记");
        // 同一个指标挂两个名字，就是「口径分裂」的另一种形态
        assertFalse(fulfillment.formula().equals(completion.formula()),
                "履约率与完成率（按时）的公式必须不同 —— 两个名字一个数是禁止的");
        // 履约率在概览与健康度两个出口出现，必须登记在同一条指标下
        assertTrue(fulfillment.bindings().size() >= 2,
                "履约率是同一个指标的两个出口（概览 / 健康度），应登记在同一条指标下");
    }

    @Test
    @DisplayName("V3.5 的四条异常计数指标都绑定到异常条目的 count 字段")
    void v3_5ExceptionMetricsShouldBindToItemCount() {
        for (String key : List.of(
                "operations.exception_manual_required_count",
                "operations.exception_match_warning_count",
                "operations.exception_complaint_rate_count",
                "operations.exception_medication_missed_count")) {
            MetricDictionary.Metric metric = dictionary.find(key);
            assertNotNull(metric, key + " 没有登记");
            assertTrue(metric.bindings().stream()
                            .anyMatch(b -> b.vo().equals("OperationsExceptionItemVO") && b.field().equals("count")),
                    key + " 必须绑定到 OperationsExceptionItemVO#count");
        }
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    /** 取字段类型里引用到的 vo 包下的类（含 {@code List<HealthPendingItemVO>} 这种泛型参数） */
    private List<Class<?>> referencedVoTypes(Field field) {
        List<Class<?>> result = new ArrayList<>();
        collectVoTypes(field.getType(), result);
        for (Type generic : field.getGenericType() instanceof ParameterizedType pt
                ? pt.getActualTypeArguments() : new Type[0]) {
            if (generic instanceof Class<?> raw) {
                collectVoTypes(raw, result);
            }
        }
        return result;
    }

    private void collectVoTypes(Class<?> type, List<Class<?>> out) {
        if (type.getName().startsWith(MetricDictionary.VO_PACKAGE)) {
            out.add(type);
        }
    }

    /** 二进制名里的包前缀去掉；嵌套类保留 {@code Outer$Inner} 形式 */
    private String simpleNameOf(Class<?> type) {
        return type.getName().substring(MetricDictionary.VO_PACKAGE.length());
    }

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
