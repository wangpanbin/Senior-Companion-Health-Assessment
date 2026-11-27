package org.company.nianglin.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 实时位置追踪配置（ADR-0022）。
 *
 * <p>绑定 {@code application.yml} 的 {@code nianglin.tracking.*}。</p>
 *
 * <h3>为什么这些值必须配置化</h3>
 *
 * <p>抽稀与限流的取值是「体验 / 成本 / 可取证性」三者的权衡，不是技术常量：
 * 上报间隔调小 → 更实时但轨迹膨胀；抽稀阈值调大 → 表更小但「他在某处停了 40 分钟」
 * 这类事实会消失。运营与答辩现场对这三者的偏好完全不同，写死在代码里改一次就要发版。</p>
 *
 * <h3>为什么必须在启动期校验</h3>
 *
 * <p>这五个值配错<b>都不会报错</b>：{@code min-move-meters=0} 会让每一帧都落库，
 * {@code latest-ttl-seconds=1} 会让家属永远拿到空位置，
 * {@code stale-threshold-seconds} 小于上报间隔会让位置一直显示「未更新」。
 * 三种症状都表现为「地图安静地不对」，事后定位成本极高 ——
 * 所以让它在启动的十秒内就失败（与 {@link MatchingProperties} 同一口径）。</p>
 *
 * @author 银龄伴诊团队
 * @since ADR-0022
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "nianglin.tracking")
public class TrackingProperties {

    /**
     * 同一订单两次上报之间的最小间隔（秒）。
     *
     * <p>前端 30 秒一次，这里用 10 秒兜住重复提交与连点（网络抖动导致的重发、
     * 用户狂点「刷新定位」）。低于该间隔回 {@code 4004}，不静默忽略。</p>
     */
    private int minReportIntervalSeconds = 10;

    /**
     * 落库抽稀阈值：与上一条轨迹点的位移达到该值（米）才写一条 {@code companion_track}。
     *
     * <p>50 米约等于「走了一个路口」。静止时不动，移动时点足够画出路径。</p>
     */
    private int minMoveMeters = 50;

    /**
     * 落库抽稀的兜底间隔（秒）：距上一条轨迹点超过该秒数也写一条。
     *
     * <p>没有这条，「在同一处停了 40 分钟」在轨迹里就是一段空白 ——
     * 而纠纷取证要的恰恰是「他没走，他在那儿等着」。</p>
     */
    private int minRecordIntervalSeconds = 120;

    /** Redis 最新点的 TTL（秒）：断连后仍能给出「最后已知位置」 */
    private int latestTtlSeconds = 300;

    /**
     * 超过该秒数未更新即在 UI 上标注「位置已 x 分钟未更新」。
     *
     * <p>{@code 180} = 6 个上报周期（30 秒 × 6）。少于此值会把正常的网络抖动
     * 渲染成告警；大于此值则家属会盯着一个 5 分钟前的点在判断「他快到了」。</p>
     */
    private int staleThresholdSeconds = 180;

    /**
     * 启动期校验：全部为正，且三个阈值之间的相对关系成立。
     *
     * <p>不满足则<b>启动失败</b>。理由见类注释：配错不会报错，只会让地图安静地不对。</p>
     */
    @PostConstruct
    void validate() {
        requirePositive("min-report-interval-seconds", minReportIntervalSeconds);
        requirePositive("min-move-meters", minMoveMeters);
        requirePositive("min-record-interval-seconds", minRecordIntervalSeconds);
        requirePositive("latest-ttl-seconds", latestTtlSeconds);
        requirePositive("stale-threshold-seconds", staleThresholdSeconds);

        if (staleThresholdSeconds < minReportIntervalSeconds) {
            throw new IllegalStateException(
                    "nianglin.tracking.stale-threshold-seconds（" + staleThresholdSeconds
                            + "）不能小于 min-report-interval-seconds（" + minReportIntervalSeconds
                            + "）—— 否则每一次正常上报之间，位置都会被标成「已停止更新」");
        }
        if (latestTtlSeconds <= minReportIntervalSeconds) {
            throw new IllegalStateException(
                    "nianglin.tracking.latest-ttl-seconds（" + latestTtlSeconds
                            + "）必须大于 min-report-interval-seconds（" + minReportIntervalSeconds
                            + "）—— 否则最新点会在下一次上报到来之前就过期，家属总是拿不到位置");
        }
        log.info("实时位置配置就绪 | 限流={}s | 抽稀位移={}m | 抽稀间隔={}s | 最新点TTL={}s | 陈旧阈值={}s",
                minReportIntervalSeconds, minMoveMeters, minRecordIntervalSeconds,
                latestTtlSeconds, staleThresholdSeconds);
    }

    private static void requirePositive(String name, int value) {
        if (value <= 0) {
            throw new IllegalStateException(
                    "nianglin.tracking." + name + " 必须大于 0，当前为 " + value);
        }
    }
}
