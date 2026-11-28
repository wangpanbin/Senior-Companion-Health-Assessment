package org.company.nianglin.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.service.GovernanceTimeoutService;
import org.company.nianglin.util.RedisLockUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 治理链超时扫描任务（closure-2026-11 · P1-6）。
 *
 * <h3>锁的粒度为什么是「本轮」</h3>
 *
 * <p>照抄 {@link FulfillmentScheduler} 的结论：超时提醒<b>漏跑一次可以接受</b>
 * （下一轮会重算并补上），<b>重复执行不可接受</b>（会重复打扰管理员）。
 * 所以锁的策略是「宁可不执行，也不并发执行」，TTL 取 25 分钟 ——
 * 小于扫描间隔 30 分钟，保证下一轮能正常抢到锁。</p>
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
@ConditionalOnProperty(prefix = "nianglin.governance", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class GovernanceTimeoutScheduler {

    /**
     * 扫描锁：TTL 25 分钟，小于扫描间隔（30 分钟）。
     */
    private static final String LOCK_SCAN = "governance:timeout:scan";

    private final GovernanceTimeoutService governanceTimeoutService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 每 30 分钟扫描一次处理中超时的投诉与反馈，提醒管理员。
     *
     * <p>半小时一轮：超时提醒的时效性比分钟级告警弱得多（阈值本身是天级），
     * 而频繁扫库没有收益。</p>
     */
    @Scheduled(
            fixedRateString = "${nianglin.governance.timeout-scan-interval-ms:1800000}",
            initialDelayString = "${nianglin.governance.timeout-scan-initial-delay-ms:120000}")
    public void scanOverdue() {
        try (RedisLockUtil.Lock lock = RedisLockUtil.tryLock(
                redisTemplate, LOCK_SCAN, Duration.ofMinutes(25))) {
            if (!lock.acquired()) {
                return;
            }
            // 一个类扫两条链（P1-6）：投诉 3 天、反馈 7 天，判定与去重共用一份策略。
            // 两条链各自独立计数 —— 一条链空转不应掩盖另一条链的提醒数。
            int complaintNotified = governanceTimeoutService.scanOverdueComplaints();
            int feedbackNotified = governanceTimeoutService.scanOverdueFeedback();
            int notified = complaintNotified + feedbackNotified;
            if (notified > 0) {
                log.info("治理链超时扫描任务结束 | 投诉链提醒={} 条 | 反馈链提醒={} 条",
                        complaintNotified, feedbackNotified);
            }
        } catch (Exception e) {
            log.error("治理链超时扫描失败 | {}", e.getMessage(), e);
        }
    }
}
