package org.company.nianglin.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.service.OrderTimeoutService;
import org.company.nianglin.util.RedisLockUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 订单超时扫描任务（closure-2026-11 · P1-1）。
 *
 * <h3>锁的粒度为什么是「本轮」</h3>
 *
 * <p>照抄 {@link FulfillmentScheduler} 的结论：超时处理<b>漏跑一次可以接受</b>
 * （下一轮会按就诊时间重算并补上），<b>重复执行不可接受</b>
 * （会重复打扰家属、重复写事件）。所以锁的策略是「宁可不执行，也不并发执行」，
 * TTL 取 8 分钟 —— 小于扫描间隔 10 分钟，保证下一轮能正常抢到锁。</p>
 *
 * <h3>为什么异常必须自己接住</h3>
 *
 * <p>{@code @Scheduled} 方法抛出异常只会被记一条日志，但一个未被捕获的异常会
 * <b>打断当前调度轮次</b>，让「下一次间隔后再来」这件事变得不确定。
 * 任务本身幂等，失败后重跑即可。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "nianglin.order", name = "timeout-scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class OrderTimeoutScheduler {

    /**
     * 扫描锁：TTL 8 分钟，小于扫描间隔（10 分钟）。
     *
     * <p>留出 2 分钟余量，保证上一轮即便跑满批次也已在下一轮开始前自动释放。</p>
     */
    private static final String LOCK_SCAN = "order:timeout:scan";

    private final OrderTimeoutService orderTimeoutService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 每 10 分钟扫描一次订单超时。
     *
     * <p>{@code fixedRate} 而不是 {@code fixedDelay}：以「每 10 分钟一轮」为准，
     * 而不是「上一轮结束后再等 10 分钟」。超时告警的时效性比机器负载重要，
     * 而单轮被批次上限约束，不会无限拖长。</p>
     */
    @Scheduled(
            fixedRateString = "${nianglin.order.timeout-scan-interval-ms:600000}",
            initialDelayString = "${nianglin.order.timeout-scan-initial-delay-ms:60000}")
    public void scanOrderTimeout() {
        try (RedisLockUtil.Lock lock = RedisLockUtil.tryLock(
                redisTemplate, LOCK_SCAN, Duration.ofMinutes(8))) {
            if (!lock.acquired()) {
                return;
            }
            int handled = orderTimeoutService.scanTimeouts();
            if (handled > 0) {
                log.info("订单超时扫描任务结束 | 本轮处理={} 条", handled);
            }
        } catch (Exception e) {
            log.error("订单超时扫描失败 | {}", e.getMessage(), e);
        }
    }
}
