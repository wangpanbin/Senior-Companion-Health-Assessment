package org.company.nianglin.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 智能调度配置（V3.4 · T3）。
 *
 * <p>绑定 {@code application.yml} 的 {@code nianglin.matching.*}。</p>
 *
 * <h3>为什么权重必须配置化</h3>
 *
 * <p>迭代计划 §38 给的初始权重（区域 30% / 时间 25% / 距离 20% / 服务能力 15% / 评分 10%）
 * 只是<b>初始配置</b>。运营会发现「某个时段供给不足」时想调权重 ——
 * 权重写死在代码里，改一次就要重新发版，而且改的过程中线上一直在按旧权重排序。</p>
 *
 * <h3>为什么权重之和必须等于 1</h3>
 *
 * <p>权重之和不为 1 时，加权总分会超出 {@code [0,1]} 或整体被压低 ——
 * 而这种错误<b>不会报错</b>，只会让排序悄悄变得不合理。
 * 所以这里在启动期直接失败：错误的配置应该在启动的十秒内暴露，
> 而不是等到运营抱怨「推荐不准」时才去翻日志。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "nianglin.matching")
public class MatchingProperties {

    /** 区域匹配权重 */
    private double regionWeight = 0.30D;

    /** 时间匹配权重 */
    private double timeWeight = 0.25D;

    /** 距离权重 */
    private double distanceWeight = 0.20D;

    /** 服务能力权重 */
    private double skillWeight = 0.15D;

    /** 评分权重 */
    private double ratingWeight = 0.10D;

    /** 距离归一化的满量程（米）：距离达到该值时得分归 0 */
    private int distanceMaxMeters = 30_000;

    /** 服务能力归一化的满量程（单）：该科室完成数达到该值时得分归 1 */
    private int skillMaxOrders = 20;

    /** 大厅候选集上限（超出时记 warn 日志，不静默截断） */
    private int hallCandidateLimit = 500;

    /** 候选推荐端点默认返回条数 */
    private int recommendLimit = 10;

    /** 候选推荐端点返回条数上限 */
    private int recommendMaxLimit = 20;

    /**
     * 档期冲突判定的时间窗（分钟）
     *
     * <p>一次陪诊服务从就诊到结束大约 2–3 小时，所以「同一时段已有在手订单」
     * 用一个可配置的窗口来判定，而不是精确到分钟 —— 精确判定需要
     * 每单都有真实的起止时间，而存量订单并不都有。</p>
     */
    private int conflictWindowMinutes = 180;

    /**
     * 启动期校验：权重非负且之和为 1；量程为正。
     *
     * <p>不满足则<b>启动失败</b>。理由写在类注释里：错误的权重不会报错，
     * 只会让排序悄悄变得不合理 —— 那是最难定位的一类故障。</p>
     */
    @PostConstruct
    void validate() {
        double sum = regionWeight + timeWeight + distanceWeight + skillWeight + ratingWeight;
        if (Math.abs(sum - 1.0D) > 1e-6D) {
            throw new IllegalStateException(
                    "nianglin.matching 的五个权重之和必须等于 1，当前为 " + sum
                            + "（region=" + regionWeight + ", time=" + timeWeight
                            + ", distance=" + distanceWeight + ", skill=" + skillWeight
                            + ", rating=" + ratingWeight + "）");
        }
        if (regionWeight < 0 || timeWeight < 0 || distanceWeight < 0
                || skillWeight < 0 || ratingWeight < 0) {
            throw new IllegalStateException("nianglin.matching 的权重不能为负");
        }
        if (distanceMaxMeters <= 0) {
            throw new IllegalStateException(
                    "nianglin.matching.distance-max-meters 必须大于 0，当前为 " + distanceMaxMeters);
        }
        if (skillMaxOrders <= 0) {
            throw new IllegalStateException(
                    "nianglin.matching.skill-max-orders 必须大于 0，当前为 " + skillMaxOrders);
        }
        if (hallCandidateLimit <= 0) {
            throw new IllegalStateException(
                    "nianglin.matching.hall-candidate-limit 必须大于 0，当前为 " + hallCandidateLimit);
        }
        log.info("智能调度配置已加载 | 权重 区域={} 时间={} 距离={} 能力={} 评分={}",
                regionWeight, timeWeight, distanceWeight, skillWeight, ratingWeight);
    }
}
