package org.company.nianglin.service.support.metrics;

import org.company.nianglin.util.MetricDictionary;
import org.company.nianglin.vo.MetricDefinitionVO;
import org.company.nianglin.vo.MetricDictionaryVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标字典只读导出的单测（V3.6 · T7 · FR-Trace）。
 *
 * <h3>这个导出最容易出的错：漏字段</h3>
 *
 * <p>导出的是「口径」，而口径的完整性就是五要素（决策 6.1）：
 * 名称 / 统计范围 / 统计时间 / 数据来源 / 计算公式。
 * 少导一个，运营就拿这一条去对账时发现「这个数到底怎么算的」—— 那正是
 * 导出这个端点要解决的问题。</p>
 *
 * <p>所以本类不测「返回了多少条」（那是抄机器面），而是测
 * <b>每条导出出来的定义是不是完整的</b>。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@DisplayName("V3.6 指标字典导出：条数对齐 + 五要素齐全")
class MetricDictionaryExporterTest {

    private final MetricDictionary dictionary = MetricDictionary.load();

    private final MetricDictionaryExporter exporter = new MetricDictionaryExporter(dictionary);

    @Test
    @DisplayName("导出条数与字典登记条数一致，版本透传")
    void exportShouldMatchDictionarySize() {
        MetricDictionaryVO vo = exporter.export();

        assertEquals(dictionary.metrics().size(), vo.getTotal().intValue(),
                "导出条数必须与字典登记条数一致");
        assertEquals(vo.getTotal().intValue(), vo.getMetrics().size(),
                "total 与实际列表长度必须一致");
        assertEquals(dictionary.version(), vo.getVersion().intValue(),
                "字典版本必须透传（运营靠它判断手上这份口径是第几版）");
        assertFalse(vo.getMetrics().isEmpty(), "字典不可能是空的");
    }

    @Test
    @DisplayName("每条导出都带全五要素 + 口径版本 + 出口端点")
    void everyExportedMetricShouldDeclareAllFiveParts() {
        for (MetricDefinitionVO metric : exporter.export().getMetrics()) {
            assertNotNull(metric.getKey(), "指标 key 不能为空");
            assertNotNull(metric.getName(), metric.getKey() + " 缺少指标名称");
            assertNotNull(metric.getScope(), metric.getKey() + " 缺少统计范围");
            assertNotNull(metric.getPeriod(), metric.getKey() + " 缺少统计时间");
            assertNotNull(metric.getSource(), metric.getKey() + " 缺少数据来源");
            assertNotNull(metric.getFormula(), metric.getKey() + " 缺少计算公式");
            assertNotNull(metric.getCaliberVersion(), metric.getKey() + " 缺少口径版本");
            assertNotNull(metric.getEndpoints(), metric.getKey() + " 缺少出口端点");
            assertFalse(metric.getName().isBlank(), metric.getKey() + " 的指标名称不能为空");
            assertTrue(metric.getCaliberVersion() >= 1, metric.getKey() + " 的口径版本号必须从 1 起");
        }
    }

    @Test
    @DisplayName("导出的 key 唯一，且与机器面一一对应（多一条 / 少一条都红）")
    void exportedKeysShouldMatchDictionaryExactly() {
        Set<String> exported = new HashSet<>();
        for (MetricDefinitionVO metric : exporter.export().getMetrics()) {
            assertTrue(exported.add(metric.getKey()), "导出的指标 key 重复：" + metric.getKey());
        }

        Set<String> expected = new HashSet<>();
        for (MetricDictionary.Metric metric : dictionary.metrics()) {
            expected.add(metric.key());
        }
        assertEquals(expected, exported, "导出的 key 集合必须与机器面逐条相同");
    }

    @Test
    @DisplayName("出口端点非空：每个指标都至少有一个端点返回它")
    void everyMetricShouldExposeAtLeastOneEndpoint() {
        for (MetricDefinitionVO metric : exporter.export().getMetrics()) {
            assertFalse(metric.getEndpoints().isEmpty(),
                    metric.getKey() + " 没有任何出口端点 —— 登记了却没人返回它");
        }
    }
}
