package org.company.nianglin.service.support.metrics;

import org.company.nianglin.common.DateRange;

import java.util.List;

/**
 * 单个指标的计算器（V3.6 · T3 · FR-Effect）。
 *
 * <h3>为什么要有这层抽象</h3>
 *
 * <p>「干预效果对比」要回答的是「这次干预让下一次服务变好了吗」。
 * 如果两期各写一遍公式，两边迟早会漂移 —— 那正是 V3.2 在用药三桶上踩过的坑
 * （ADR-0014：同一个判定在两处各写各的 {@code CASE WHEN}）。</p>
 *
 * <p>所以这里的约定是：<b>一个指标 key 只能有一个实现</b>，
 * 效果对比的两期由<b>同一个计算器</b>算两遍，而不是两边各算一次。</p>
 *
 * <h3>返回字符串而不是数字</h3>
 *
 * <p>因为同一个指标在不同端点上的呈现形态不同（百分比带 {@code %}、计数不带、
 * 评分两位小数），而 {@code operations.completion_rate} 在驾驶舱里就是
 * {@code "88.20%"}。返回字符串可以让同一个计算器同时喂饱「驾驶舱」与
 * 「效果对比」两个出口，<b>不必为了展示形态再抄一份公式</b>。</p>
 *
 * <h3>跨端共用同一份实现</h3>
 *
 * <p>{@link #aliasKeys()} 用于「同一口径、不同指标 key」的场景
 * （如 {@code operations.fulfillment_rate} 与 {@code companion.workbench.completion_rate}
 * 的公式逐字相同）。这时注册表里只放<b>一个</b>计算器，两个 key 都指向它 ——
 * 决策 6.2 说的是「一个口径」，不是「一个 key 一份代码」。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
public interface MetricCalculator {

    /**
     * 本计算器的主指标 key。
     *
     * <p>必须在指标字典（{@code metrics/metric-dictionary.yml}）里登记过。</p>
     *
     * @return 指标 key
     */
    String metricKey();

    /**
     * 按区间算该指标的值。
     *
     * <p>实现必须满足：</p>
     * <ul>
     *   <li>空数据返回 {@code "0"} / {@code "0.00%"} / {@code "0.00"}，
     *       <b>不返回 {@code null}、不抛异常</b>；</li>
     *   <li>同一区间两次调用必须得到同一个值（无随机、无「现在时间」依赖）；</li>
     *   <li>口径必须与指标字典里登记的公式<b>逐字同源</b>。</li>
     * </ul>
     *
     * @param range 统计区间（闭区间）
     * @return 指标值的字符串形态
     */
    String calculate(DateRange range);

    /**
     * 与主指标<b>同口径</b>的其它指标 key（跨端共用同一份实现，决策 6.2）。
     *
     * <p>默认没有别名 —— 只有公式逐字相同、只是出口不同的指标才需要登记。</p>
     *
     * @return 别名 key 列表；默认为空
     */
    default List<String> aliasKeys() {
        return List.of();
    }
}
