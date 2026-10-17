package org.company.nianglin.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.service.support.FulfillmentRiskEvaluator;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 履约保障配置（V3.1 · T1）。
 *
 * <p>绑定 {@code application.yml} 的 {@code nianglin.fulfillment.*}。</p>
 *
 * <h3>为什么单独一个 Properties 类而不是各处 {@code @Value}</h3>
 *
 * <p>本版有 9 个配置键，会被扫描任务、催单服务、大厅查询分别读取。
 * 散落的 {@code @Value} 会让「阈值到底是多少」变成一次全局搜索，
 * 而且启动期校验拿不到完整视图。这里集中一处，并由
 * {@link FulfillmentRiskEvaluator#validateThresholds} 在启动时校验顺序。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "nianglin.fulfillment")
public class FulfillmentProperties {

    /** 等待达到该分钟数升为 WARNING */
    private int warningMinutes = 10;

    /** 升为 URGENT，此时开放家属催单 */
    private int urgentMinutes = 30;

    /** 升为 MANUAL_REQUIRED，出现在履约风险中心红区 */
    private int manualRequiredMinutes = 120;

    /** 家属两次催单之间的最小间隔（分钟） */
    private int urgeCooldownMinutes = 10;

    /** 单笔订单允许的催单次数上限 */
    private int urgeMaxTimes = 3;

    /** 风险扫描任务总开关 */
    private boolean schedulerEnabled = true;

    /** 单轮扫描处理的候选订单上限 */
    private int scanBatch = 200;

    /** 扫描间隔（毫秒） */
    private long schedulerIntervalMs = 300_000L;

    /** 首次扫描前的延迟（毫秒） */
    private long schedulerInitialDelayMs = 60_000L;

    /** 订单大厅默认排序；V3.1 只实现 RECOMMEND */
    private String hallDefaultSort = "RECOMMEND";

    /**
     * 启动期校验：阈值必须严格递增，且催单参数必须为正。
     *
     * <p>不满足则<b>启动失败</b>而不是打条 warn 继续跑。理由写在
     * {@link FulfillmentRiskEvaluator#validateThresholds} 的注释里：
     * 反向阈值会表现为「先紧急后预警」，这种行为在生产上极难定位，
     * 与其事后排查，不如在启动的十秒内就失败。</p>
     */
    @PostConstruct
    void validate() {
        FulfillmentRiskEvaluator.validateThresholds(warningMinutes, urgentMinutes, manualRequiredMinutes);
        if (urgeCooldownMinutes <= 0) {
            throw new IllegalStateException(
                    "nianglin.fulfillment.urge-cooldown-minutes 必须大于 0，当前为 " + urgeCooldownMinutes);
        }
        if (urgeMaxTimes <= 0) {
            throw new IllegalStateException(
                    "nianglin.fulfillment.urge-max-times 必须大于 0，当前为 " + urgeMaxTimes);
        }
        log.info("履约保障配置已加载 | 预警={}分钟 紧急={}分钟 人工介入={}分钟 | 催单冷却={}分钟 上限={}次",
                warningMinutes, urgentMinutes, manualRequiredMinutes,
                urgeCooldownMinutes, urgeMaxTimes);
    }
}
