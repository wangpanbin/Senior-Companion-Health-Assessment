package org.company.nianglin.util;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「端点 ↔ 指标」机器面的读取器（V3.6 · T1 · FR-Dict）。
 *
 * <h3>它解决什么问题</h3>
 *
 * <p>指标字典（{@code metrics/metric-dictionary.yml}）回答的是「这个指标怎么算」，
 * 但没有回答「它出现在哪个端点上」。于是出现了一个很常见的静默失守：
 * 端点上多了一个数字，字典里没登记 —— 或者字典里登记了一个指标，
 * 端点上却没有出口，运营拿着文档去找那个数，找不到。</p>
 *
 * <p>本类读的是<b>反向索引</b>：端点 → 它返回的指标 key。
 * {@code MetricEndpointContractTest} 用它做三条双向核对：</p>
 * <ol>
 *   <li>这里声明的端点必须真实存在（反射扫描 {@code @RestController}）；</li>
 *   <li>这里声明的指标必须在指标字典里；</li>
 *   <li>指标字典里每条指标声明的端点，必须在这里出现。</li>
 * </ol>
 *
 * <h3>为什么用 SnakeYAML 而不是 Jackson</h3>
 *
 * <p>与 {@link MetricDictionary} 同源：Spring Boot 的 starter 已经带了
 * {@code org.yaml:snakeyaml}，不新增任何第三方依赖（AGENTS.md §0.2）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
public final class MetricEndpointRegistry {

    /** 端点表在 classpath 中的位置 */
    public static final String RESOURCE = "metrics/endpoints.yml";

    /** 一个端点及其返回的指标 key */
    public record Endpoint(String endpoint, List<String> metrics) {
    }

    private final int version;
    private final List<Endpoint> endpoints;

    /** 端点 → 指标列表（保持 yml 里的顺序） */
    private final Map<String, List<String>> metricsByEndpoint;

    /** 指标 key → 声明它的端点列表（保持 yml 里的顺序） */
    private final Map<String, List<String>> endpointsByMetric;

    private MetricEndpointRegistry(int version, List<Endpoint> endpoints) {
        this.version = version;
        this.endpoints = endpoints;

        Map<String, List<String>> byEndpoint = new LinkedHashMap<>();
        Map<String, List<String>> byMetric = new LinkedHashMap<>();
        for (Endpoint endpoint : endpoints) {
            byEndpoint.put(endpoint.endpoint(), endpoint.metrics());
            for (String metric : endpoint.metrics()) {
                byMetric.computeIfAbsent(metric, key -> new ArrayList<>()).add(endpoint.endpoint());
            }
        }
        this.metricsByEndpoint = byEndpoint;
        this.endpointsByMetric = byMetric;
    }

    /**
     * 从 classpath 读取端点表。
     *
     * @return 端点表；文件缺失或为空时抛出 {@link IllegalStateException}
     */
    @SuppressWarnings("unchecked")
    public static MetricEndpointRegistry load() {
        try (InputStream in = MetricEndpointRegistry.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("端点↔指标机器面缺失：" + RESOURCE);
            }
            Map<String, Object> root =
                    new Yaml().load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            if (root == null) {
                throw new IllegalStateException("端点↔指标机器面为空：" + RESOURCE);
            }

            List<Endpoint> endpoints = new ArrayList<>();
            for (Map<String, Object> raw : (List<Map<String, Object>>) root.getOrDefault("endpoints", List.of())) {
                String endpoint = str(raw.get("endpoint"));
                if (endpoint == null || endpoint.isBlank()) {
                    throw new IllegalStateException("端点↔指标机器面存在没有 endpoint 字段的条目：" + RESOURCE);
                }
                List<String> metrics = new ArrayList<>();
                for (Object metric : (List<Object>) raw.getOrDefault("metrics", List.of())) {
                    metrics.add(String.valueOf(metric));
                }
                endpoints.add(new Endpoint(endpoint.trim(), List.copyOf(metrics)));
            }

            return new MetricEndpointRegistry(
                    root.get("version") == null ? 0 : ((Number) root.get("version")).intValue(),
                    List.copyOf(endpoints));
        } catch (java.io.IOException e) {
            throw new IllegalStateException("端点↔指标机器面读取失败：" + RESOURCE, e);
        }
    }

    /** 端点表版本 */
    public int version() {
        return version;
    }

    /** 全部端点登记 */
    public List<Endpoint> endpoints() {
        return Collections.unmodifiableList(endpoints);
    }

    /**
     * 取某端点返回的指标 key。
     *
     * @param endpoint 端点（形如 {@code "GET /api/admin/operations/overview"}）
     * @return 指标 key 列表；端点未登记时返回空列表（不抛异常、不返回 {@code null}）
     */
    public List<String> metricsOf(String endpoint) {
        if (endpoint == null) {
            return List.of();
        }
        return metricsByEndpoint.getOrDefault(endpoint.trim(), List.of());
    }

    /**
     * 取某指标出现在哪些端点上。
     *
     * @param metricKey 指标 key
     * @return 端点列表；指标未登记时返回空列表（不抛异常、不返回 {@code null}）
     */
    public List<String> endpointsOf(String metricKey) {
        if (metricKey == null) {
            return List.of();
        }
        return endpointsByMetric.getOrDefault(metricKey.trim(), List.of());
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
