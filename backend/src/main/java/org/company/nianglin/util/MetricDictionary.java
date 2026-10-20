package org.company.nianglin.util;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 指标字典的机器面读取器（V3.2 · T-0 · FR-Metric）。
 *
 * <h3>它解决什么问题</h3>
 *
 * <p>同一个指标在不同角色端出现不同口径，是「数据看起来对、合起来不对」的
 * 头号原因。而靠注释约束口径是<b>最不可靠</b>的一种约束 ——
 * 注释不会在改动时报警，只会在事后被人读错。</p>
 *
 * <p>所以指标先在字典里登记（含名称 / 范围 / 时间 / 来源 / 公式五要素），
 * 才允许出现在任何 VO 里；{@code MetricDictionaryTest} 会双向校验：
 * 字典里写的字段必须真的存在，VO 里的每个字段也必须真的登记过。</p>
 *
 * <h3>为什么用 SnakeYAML 而不是 Jackson</h3>
 *
 * <p>Spring Boot 的 starter 已经带了 {@code org.yaml:snakeyaml}（编译期依赖），
 * 不需要为这份字典新增任何第三方依赖（AGENTS.md §0.2）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
public final class MetricDictionary {

    /** 字典文件在 classpath 中的位置 */
    public static final String RESOURCE = "metrics/metric-dictionary.yml";

    /** 指标 VO 所在的包（字典里的类名是简单名） */
    public static final String VO_PACKAGE = "org.company.nianglin.vo.";

    /** 一条指标与某个 VO 字段的绑定 */
    public record Binding(String vo, String field) {
    }

    /** 一条已登记的指标 */
    public record Metric(String key, String name, String scope, String period, String source,
                         String formula, int caliberVersion, List<String> endpoints,
                         List<Binding> bindings) {
    }

    /** 一个 VO 的字段构成：哪些是指标，哪些是结构字段 */
    public record VoSpec(String className, List<String> structural) {
    }

    private final int version;
    private final List<Metric> metrics;
    private final List<VoSpec> vos;

    private MetricDictionary(int version, List<Metric> metrics, List<VoSpec> vos) {
        this.version = version;
        this.metrics = metrics;
        this.vos = vos;
    }

    /**
     * 从 classpath 读取字典。
     *
     * @return 字典；文件缺失时抛出 {@link IllegalStateException}
     */
    @SuppressWarnings("unchecked")
    public static MetricDictionary load() {
        try (InputStream in = MetricDictionary.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("指标字典缺失：" + RESOURCE);
            }
            Map<String, Object> root = new Yaml().load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            if (root == null) {
                throw new IllegalStateException("指标字典为空：" + RESOURCE);
            }

            List<Metric> metrics = new ArrayList<>();
            for (Map<String, Object> raw : (List<Map<String, Object>>) root.getOrDefault("metrics", List.of())) {
                List<Binding> bindings = new ArrayList<>();
                for (Map<String, Object> binding :
                        (List<Map<String, Object>>) raw.getOrDefault("bindings", List.of())) {
                    bindings.add(new Binding(str(binding.get("vo")), str(binding.get("field"))));
                }
                metrics.add(new Metric(
                        str(raw.get("key")),
                        str(raw.get("name")),
                        str(raw.get("scope")),
                        str(raw.get("period")),
                        str(raw.get("source")),
                        str(raw.get("formula")),
                        raw.get("caliberVersion") == null ? 1 : ((Number) raw.get("caliberVersion")).intValue(),
                        strings(raw.get("endpoints")),
                        bindings));
            }

            List<VoSpec> vos = new ArrayList<>();
            for (Map<String, Object> raw : (List<Map<String, Object>>) root.getOrDefault("vos", List.of())) {
                vos.add(new VoSpec(str(raw.get("class")), strings(raw.get("structural"))));
            }

            return new MetricDictionary(
                    root.get("version") == null ? 0 : ((Number) root.get("version")).intValue(),
                    metrics, vos);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("指标字典读取失败：" + RESOURCE, e);
        }
    }

    /** 字典版本 */
    public int version() {
        return version;
    }

    /** 全部已登记指标 */
    public List<Metric> metrics() {
        return Collections.unmodifiableList(metrics);
    }

    /** 全部 VO 构成声明 */
    public List<VoSpec> vos() {
        return Collections.unmodifiableList(vos);
    }

    /** 按 key 取指标；不存在返回 {@code null} */
    public Metric find(String key) {
        for (Metric metric : metrics) {
            if (metric.key().equals(key)) {
                return metric;
            }
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> strings(Object value) {
        if (value == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object item : (List<Object>) value) {
            result.add(String.valueOf(item));
        }
        return result;
    }
}
