package org.company.nianglin.service.support.metrics;

import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.util.MetricDictionary;
import org.company.nianglin.vo.MetricDefinitionVO;
import org.company.nianglin.vo.MetricDictionaryVO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 指标字典的只读导出器（V3.6 · T7 · FR-Trace）。
 *
 * <h3>它解决「运营拿什么对账」</h3>
 *
 * <p>驾驶舱上的一串数字，运营看得见、但看不出它是<b>怎么算</b>的 ——
 * 指标字典（机器面 {@code metrics/metric-dictionary.yml}）里有答案，
 * 可那是 classpath 里的 yml，浏览器打不开。本类把它投影成一份可读的 JSON，
 * 让「看板上的数」和「它的定义」能在同一个地方对照。</p>
 *
 * <h3>为什么字典在构造期就加载</h3>
 *
 * <p>{@link MetricDictionary#load()} 在字典文件缺失时抛异常 —— 那是
 * <b>启动期失败</b>，而不是「接口调用时才发现字典没了」。
 * 字典是整个统计口径的根，根断了还让服务正常启动，只会把错误留到某个
 * 运营在看板上提问的那天。</p>
 *
 * <p>字典是静态资源，只在构造期加载一次；导出的是<b>定义</b>，
 * 不涉及任何数据查询，因此本类<b>不依赖 Mapper</b>。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Slf4j
@Component
public class MetricDictionaryExporter {

    /** 机器面字典（构造期加载，缺失即启动失败） */
    private final MetricDictionary dictionary;

    public MetricDictionaryExporter() {
        this(MetricDictionary.load());
    }

    /** 构造时注入字典（测试可传入固定字典，避免依赖 classpath） */
    public MetricDictionaryExporter(MetricDictionary dictionary) {
        this.dictionary = dictionary;
    }

    /**
     * 导出字典。
     *
     * @return 字典版本 + 全部指标定义（按登记顺序）
     */
    public MetricDictionaryVO export() {
        List<MetricDefinitionVO> metrics = new ArrayList<>();
        for (MetricDictionary.Metric metric : dictionary.metrics()) {
            metrics.add(new MetricDefinitionVO()
                    .setKey(metric.key())
                    .setName(metric.name())
                    .setScope(metric.scope())
                    .setPeriod(metric.period())
                    .setSource(metric.source())
                    .setFormula(metric.formula())
                    .setCaliberVersion(metric.caliberVersion())
                    .setEndpoints(List.copyOf(metric.endpoints())));
        }
        log.debug("导出指标字典 | 版本 {} 共 {} 条", dictionary.version(), metrics.size());
        return new MetricDictionaryVO()
                .setVersion(dictionary.version())
                .setTotal(metrics.size())
                .setMetrics(metrics);
    }
}
