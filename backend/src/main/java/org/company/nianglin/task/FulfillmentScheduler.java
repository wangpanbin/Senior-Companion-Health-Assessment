package org.company.nianglin.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.service.FulfillmentService;
import org.company.nianglin.util.RedisLockUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 履约风险扫描任务（V3.1 · T2）。
 *
 * <h3>锁的粒度为什么是「本轮」</h3>
 *
 * <p>照抄 {@link MedicationScheduler} 的结论：风险升级<b>漏跑一次可以接受</b>
 * （下一轮会按等待时长重算并补上），<b>重复执行不可接受</b>
 * （会重复打扰家属、重复写事件）。所以锁的策略是「宁可不执行，也不并发执行」，
 * TTL 取 4 分钟 —— 小于扫描间隔 5 分钟，保证下一轮能正常抢到锁。</p>
 *
 * <p>与用药任务「锁一整天」的差别在于：生成任务漏跑会当天没有药可吃，
 * 代价不可接受；风险升级漏跑只是晚 5 分钟告警，代价可接受。</p>
 *
 * <h3>为什么异常必须自己接住</h3>
 *
 * <p>{@code @Scheduled} 方法抛出异常只会被记一条日志，但一个未被捕获的异常会
 * <b>打断当前调度轮次</b>，让「下一次间隔后再来」这件事变得不确定。
 * 任务本身幂等，失败后重跑即可。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "nianglin.fulfillment", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class FulfillmentScheduler {

    /**
     * 扫描锁：TTL 4 分钟，小于扫描间隔（5 分钟）。
     *
     * <p>留出 1 分钟余量，保证上一轮即便跑满批次也已在下一轮开始前自动释放。</p>
     */
    private static final String LOCK_SCAN = "fulfillment:scan";

    private final FulfillmentService fulfillmentService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 每 5 分钟扫描一次待接单订单，升级超时的履约风险。
     *
     * <p>{@code fixedRate} 而不是 {@code fixedDelay}：以「每 5 分钟一轮」为准，
     * 而不是「上一轮结束后再等 5 分钟」。风险告警的时效性比机器负载重要，
     * 而单轮被 {@code scan-batch} 限制了上限，不会无限拖长。</p>
     */
    @Scheduled(
            fixedRateString = "${nianglin.fulfillment.scheduler-interval-ms:300000}",
            initialDelayString = "${nianglin.fulfillment.scheduler-initial-delay-ms:60000}")
    public void scanFulfillmentRisk() {
        try (RedisLockUtil.Lock lock = RedisLockUtil.tryLock(
                redisTemplate, LOCK_SCAN, Duration.ofMinutes(4))) {
            if (!lock.acquired()) {
                return;
            }
            int escalated = fulfillmentService.scanAndEscalate();
            if (escalated > 0) {
                log.info("履约风险扫描任务结束 | 本轮升级={} 条", escalated);
            }
        } catch (Exception e) {
            log.error("履约风险扫描失败 | {}", e.getMessage(), e);
        }
    }
}
