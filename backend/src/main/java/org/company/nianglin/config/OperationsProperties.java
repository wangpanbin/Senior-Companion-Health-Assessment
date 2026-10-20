package org.company.nianglin.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 平台运营驾驶舱配置（V3.5 · T1）。
 *
 * <p>绑定 {@code application.yml} 的 {@code nianglin.operations.*}。</p>
 *
 * <h3>为什么单独一个 Properties 类</h3>
 *
 * <p>这 6 个键分别被「概览 / 健康度」「异常中心」「趋势」三处读取，
 * 散落的 {@code @Value} 会让「阈值到底是多少」变成一次全局搜索。
 * 这里集中一处，并在启动期把不合法���置<b>直接变成启动失败</b> ——
 * 理由与 {@link FulfillmentProperties} 相同：错误的阈值不会报错，
 * 只会让驾驶舱安静地给出错误的结论，比启动失败难查得多。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.5
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "nianglin.operations")
public class OperationsProperties {

    /**
     * 「按时完成」的判定窗口（分钟）：{@code finish_time <= visit_time + 窗口}。
     *
     * <p>用于「完成率（按时）」这个指标 —— 它与「履约率」不是同一个数，
     * 详见 tickets §偏差 D-3.5-1。</p>
     */
    private int fulfillmentWindowMinutes = 240;

    /**
     * 投诉率异常阈值（0–1）。陪诊员的投诉率<b>严格大于</b>该值才算异常。
     */
    private double complaintRateThreshold = 0.20;

    /**
     * 投诉率统计的回溯天数（异常中心专用，与概览的统计区间无关）。
     */
    private int complaintLookbackDays = 30;

    /**
     * 计算投诉率时的最小接单量：接单量小于该值的陪诊员不参与判定
     * （样本太小，比率没有意义）。
     */
    private int complaintMinOrders = 3;

    /**
     * 用药漏服异常的阈值（漏服任务数）。
     */
    private int medicationMissedThreshold = 5;

    /**
     * 趋势区间上限（天）。与共享区间 {@code DateRange.MAX_RANGE_DAYS} 同源，
     * 但独立配置：趋势图比概览更怕超长区间（一年 366 个点在前端已经很挤）。
     */
    private int trendMaxDays = 366;

    /**
     * 启动期校验：阈值必须为正；投诉率阈值必须在 {@code (0, 1]} 内。
     *
     * <p>不满足则<b>启动失败</b>而不是打条 warn 继续跑 ——
     * 「投诉率阈值 = 0」会让每一单投诉的陪诊员都变成异常，
     * 「投诉率阈值 = 1.5」则永远不会有人异常，两者都不会报错，
     * 只会让运营对着一个恒定报警或恒不报警的看板做决策。</p>
     */
    @PostConstruct
    void validate() {
        if (fulfillmentWindowMinutes <= 0) {
            throw new IllegalStateException(
                    "nianglin.operations.fulfillment-window-minutes 必须大于 0，当前为 " + fulfillmentWindowMinutes);
        }
        if (complaintRateThreshold <= 0 || complaintRateThreshold > 1) {
            throw new IllegalStateException(
                    "nianglin.operations.complaint-rate-threshold 必须在 (0, 1] 内，当前为 " + complaintRateThreshold);
        }
        if (complaintLookbackDays <= 0) {
            throw new IllegalStateException(
                    "nianglin.operations.complaint-lookback-days 必须大于 0，当前为 " + complaintLookbackDays);
        }
        if (complaintMinOrders <= 0) {
            throw new IllegalStateException(
                    "nianglin.operations.complaint-min-orders 必须大于 0，当前为 " + complaintMinOrders);
        }
        if (medicationMissedThreshold <= 0) {
            throw new IllegalStateException(
                    "nianglin.operations.medication-missed-threshold 必须大于 0，当前为 " + medicationMissedThreshold);
        }
        if (trendMaxDays <= 0) {
            throw new IllegalStateException(
                    "nianglin.operations.trend-max-days 必须大于 0，当前为 " + trendMaxDays);
        }
        log.info("运营驾驶舱配置已加载 | 按时完成窗口={}分钟 投诉率阈值={} 回溯={}天 最小接单量={} "
                        + "漏服阈值={} 趋势上限={}天",
                fulfillmentWindowMinutes, complaintRateThreshold, complaintLookbackDays,
                complaintMinOrders, medicationMissedThreshold, trendMaxDays);
    }
}
