package org.company.nianglin.service.support.metrics;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 指标计算器注册表（V3.6 · T3 · FR-Effect）。
 *
 * <h3>它兜住两件事</h3>
 *
 * <ol>
 *   <li><b>一个 key 只能有一个实现</b>：两个计算器声明同一个 key 时
 *       <b>启动失败</b>，而不是「后注册的覆盖先注册的」。
 *       后者会让口径变成「取决于 Bean 的加载顺序」——
 *       这类问题不会报错，只会让两个端点给出两个数。</li>
 *   <li><b>未注册的指标一律取不到</b>：效果对比端点对没注册的 key 返回 400，
 *       <b>绝不猜一个算法算出来</b>。</li>
 * </ol>
 *
 * <h3>为什么重复 key 要「启动失败」</h3>
 *
 * <p>照抄 {@code MatchingProperties#validate} 的做法：错误的配置不会报错，
 * 只会让看板安静地给出错误的结论，比启动失败难查得多。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@Slf4j
@Component
public class MetricCalculatorRegistry {

    /** 指标 key → 计算器（保持注入顺序） */
    private final Map<String, MetricCalculator> calculators = new LinkedHashMap<>();

    /**
     * 构造注册表。
     *
     * @param calculators Spring 注入的全部计算器实现
     * @throws IllegalStateException 同一个指标 key 被声明了两次（启动失败）
     */
    public MetricCalculatorRegistry(List<MetricCalculator> calculators) {
        for (MetricCalculator calculator : calculators) {
            register(calculator.metricKey(), calculator);
            for (String alias : calculator.aliasKeys()) {
                register(alias, calculator);
            }
        }
        log.info("指标计算器注册表已加载 | 可计算指标 {} 条", this.calculators.size());
    }

    private void register(String key, MetricCalculator calculator) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("指标计算器的 key 不能为空："
                    + calculator.getClass().getName());
        }
        MetricCalculator existing = calculators.putIfAbsent(key, calculator);
        if (existing != null) {
            throw new IllegalStateException("指标 key 被声明了两次：" + key
                    + "（" + existing.getClass().getName() + " 与 " + calculator.getClass().getName()
                    + "）—— 一个指标只能有一个实现");
        }
    }

    /**
     * 按 key 取计算器。
     *
     * @param key 指标 key
     * @return 计算器；未注册时返回 {@code null}（调用方据此返回 400）
     */
    public MetricCalculator find(String key) {
        if (key == null) {
            return null;
        }
        return calculators.get(key.trim());
    }

    /**
     * 是否可计算。
     *
     * @param key 指标 key
     * @return 已注册返回 {@code true}
     */
    public boolean supports(String key) {
        return find(key) != null;
    }

    /** 全部可计算的指标 key */
    public Set<String> keys() {
        return Collections.unmodifiableSet(calculators.keySet());
    }

    /** 全部可计算的指标 key（列表形态，保持顺序） */
    public List<String> keyList() {
        return List.copyOf(new ArrayList<>(calculators.keySet()));
    }
}
