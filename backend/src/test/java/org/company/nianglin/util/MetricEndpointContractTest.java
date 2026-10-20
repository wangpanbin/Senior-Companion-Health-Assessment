package org.company.nianglin.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「端点 ↔ 指标」的双向校验（V3.6 · T2 · FR-Dict）。
 *
 * <h3>这个测试在防什么</h3>
 *
 * <p>指标字典（V3.2 起）管住了「这个指标怎么算」，但一直没人管
 * 「它出现在哪个端点上」。于是有两种静默失守：</p>
 * <ul>
 *   <li><b>端点上多了一个数，字典里没有</b> —— 运营看到它、文档里查不到它；</li>
 *   <li><b>字典里登记了一个指标，没有任何端点返回它</b> —— 文档承诺的东西不存在。</li>
 * </ul>
 *
 * <p>这两种失守都不会让任何测试变红，所以本测试把它们变成<b>会变红</b>的事。</p>
 *
 * <h3>三条校验（缺一不可）</h3>
 *
 * <ol>
 *   <li><b>端点 → 代码</b>：这里登记的端点必须真的存在
 *       （反射扫描所有 {@code @RestController} 的映射注解）；</li>
 *   <li><b>端点 → 字典</b>：这里登记的指标必须在指标字典里；</li>
 *   <li><b>字典 → 端点</b>：字典里每条指标声明的端点必须在这里出现。</li>
 * </ol>
 *
 * <p>外加一条<b>集合级</b>校验：某个端点返回的指标集合，
 * 必须与字典里声明的该端点指标集合<b>逐条相同</b> ——
 * 只查「包含关系」的话，字典里新增一个指标而忘了更新端点表，是查不出来的。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@DisplayName("V3.6 端点↔指标：双向一致（删一行 yml 就变红）")
class MetricEndpointContractTest {

    /** 控制器所在包（反射扫描范围） */
    private static final String CONTROLLER_PACKAGE = "org.company.nianglin.controller";

    private final MetricDictionary dictionary = MetricDictionary.load();

    private final MetricEndpointRegistry registry = MetricEndpointRegistry.load();

    /* ================================================================== */
    /* 1 · 端点必须真实存在                                                */
    /* ================================================================== */

    @Test
    @DisplayName("① 端点表里声明的端点必须真的存在（反射扫描 @RestController）")
    void everyDeclaredEndpointShouldExistInCode() {
        Set<String> actual = scanControllerEndpoints();

        assertFalse(actual.isEmpty(), "反射扫描一个端点都没扫到 —— 扫描逻辑本身坏了");
        for (MetricEndpointRegistry.Endpoint endpoint : registry.endpoints()) {
            assertTrue(actual.contains(endpoint.endpoint()),
                    "端点表里登记了不存在的端点：" + endpoint.endpoint()
                            + "（文档里写了一个接口，代码里没有）");
        }
    }

    /* ================================================================== */
    /* 2 · 指标必须在字典里                                                */
    /* ================================================================== */

    @Test
    @DisplayName("② 端点表里声明的指标必须在指标字典里")
    void everyDeclaredMetricShouldExistInDictionary() {
        for (MetricEndpointRegistry.Endpoint endpoint : registry.endpoints()) {
            for (String metric : endpoint.metrics()) {
                assertNotNull(dictionary.find(metric),
                        "端点 " + endpoint.endpoint() + " 声明的指标 " + metric
                                + " 不在指标字典里 —— 指标必须先登记再出现");
            }
        }
    }

    /* ================================================================== */
    /* 3 · 字典里的端点必须在端点表里                                      */
    /* ================================================================== */

    @Test
    @DisplayName("③ 指标字典里每条指标声明的端点必须出现在端点表里")
    void everyEndpointDeclaredByMetricShouldBeRegistered() {
        Set<String> registered = new HashSet<>();
        for (MetricEndpointRegistry.Endpoint endpoint : registry.endpoints()) {
            registered.add(endpoint.endpoint());
        }

        for (MetricDictionary.Metric metric : dictionary.metrics()) {
            for (String endpoint : metric.endpoints()) {
                assertTrue(registered.contains(endpoint),
                        "指标 " + metric.key() + " 声明的端点 " + endpoint
                                + " 没有出现在端点表里 —— 没人知道它属于哪个出口");
            }
        }
    }

    /* ================================================================== */
    /* 4 · 集合级一致（少一条 / 多一条都要红）                              */
    /* ================================================================== */

    @Test
    @DisplayName("④ 端点返回的指标集合与字典声明逐条相同（多一条或少一条都变红）")
    void endpointMetricSetsShouldMatchDictionaryExactly() {
        // 字典侧的逆索引：端点 → 声明它的指标
        java.util.Map<String, Set<String>> byEndpoint = new java.util.LinkedHashMap<>();
        for (MetricDictionary.Metric metric : dictionary.metrics()) {
            for (String endpoint : metric.endpoints()) {
                byEndpoint.computeIfAbsent(endpoint, key -> new LinkedHashSet<>()).add(metric.key());
            }
        }

        for (MetricEndpointRegistry.Endpoint endpoint : registry.endpoints()) {
            Set<String> fromRegistry = new LinkedHashSet<>(endpoint.metrics());
            Set<String> fromDictionary = byEndpoint.getOrDefault(endpoint.endpoint(), Set.of());
            assertEquals(fromDictionary, fromRegistry,
                    "端点 " + endpoint.endpoint() + " 的指标集合与指标字典不一致："
                            + "字典=" + new TreeSet<>(fromDictionary)
                            + "，端点表=" + new TreeSet<>(fromRegistry));
        }
    }

    /* ================================================================== */
    /* 5 · V3.6 的两个新端点已登记                                        */
    /* ================================================================== */

    @Test
    @DisplayName("⑤ V3.6 的两个新端点与它们的指标已登记")
    void v3_6EndpointsShouldBeRegistered() {
        assertTrue(registry.metricsOf("GET /api/admin/operations/gap").contains("operations.gap.supply_gap"),
                "供给缺口端点没有登记「时段供给缺口」指标");
        assertTrue(registry.metricsOf("GET /api/admin/operations/effect")
                        .contains("operations.fulfillment_rate"),
                "干预效果对比端点没有登记「履约率」指标");
        // 陪诊员端的完成率与平台端的履约率共用一个计算器（决策 6.2），两个出口都要登记
        assertTrue(registry.endpointsOf("companion.workbench.completion_rate")
                        .contains("GET /api/statistics/companion/me"),
                "陪诊员端完成率没有登记工作台端点");
        assertTrue(registry.endpointsOf("companion.workbench.completion_rate")
                        .contains("GET /api/admin/operations/effect"),
                "陪诊员端完成率没有登记干预效果对比端点");
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    /**
     * 反射扫描所有 {@code @RestController}，收集它们实际暴露的端点。
     *
     * @return 形如 {@code "GET /api/admin/operations/overview"} 的端点集合
     */
    private Set<String> scanControllerEndpoints() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        Set<String> endpoints = new LinkedHashSet<>();
        for (var candidate : scanner.findCandidateComponents(CONTROLLER_PACKAGE)) {
            Class<?> type;
            try {
                type = Class.forName(candidate.getBeanClassName());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("反射扫描到的类加载不了：" + candidate.getBeanClassName(), e);
            }
            List<String> prefixes = classLevelPrefixes(type);
            for (Method method : type.getDeclaredMethods()) {
                // @GetMapping / @PostMapping / @PutMapping / @DeleteMapping 都会被合并成 @RequestMapping
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                for (RequestMethod httpMethod : mapping.method()) {
                    for (String path : pathsOf(mapping)) {
                        for (String prefix : prefixes) {
                            endpoints.add(httpMethod.name() + " " + normalize(prefix + "/" + path));
                        }
                    }
                }
            }
        }
        return endpoints;
    }

    /** 类级 {@code @RequestMapping} 的前缀；没有则用空串 */
    private List<String> classLevelPrefixes(Class<?> type) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
        if (mapping == null) {
            return List.of("");
        }
        List<String> paths = pathsOf(mapping);
        return paths.isEmpty() ? List.of("") : paths;
    }

    /** 映射注解声明的路径；两者都为空时用空串（表示挂在类级前缀上） */
    private List<String> pathsOf(RequestMapping mapping) {
        List<String> paths = new ArrayList<>();
        paths.addAll(List.of(mapping.value()));
        paths.addAll(List.of(mapping.path()));
        if (paths.isEmpty()) {
            paths.add("");
        }
        return paths;
    }

    /** 规范化路径：补前导斜杠、合并重复斜杠、去掉尾部斜杠 */
    private String normalize(String path) {
        String normalized = path.replaceAll("/{2,}", "/");
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
