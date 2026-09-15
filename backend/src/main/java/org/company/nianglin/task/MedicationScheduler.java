package org.company.nianglin.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.service.MedicationService;
import org.company.nianglin.util.RedisLockUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;

/**
 * 用药模块定时任务（M6 的技术亮点）。
 *
 * <h3>两个任务，两种失败代价</h3>
 *
 * <table border="1">
 *   <caption>任务特性</caption>
 *   <tr><th>任务</th><th>频率</th><th>漏执行的后果</th><th>重复执行的后果</th></tr>
 *   <tr><td>生成当日服药任务</td><td>每天 07:00</td>
 *       <td>当天没有待服任务，老人不知道该吃药 —— <b>不可接受</b></td>
 *       <td>可能产生重复行 —— 靠唯一索引挡住</td></tr>
 *   <tr><td>漏服扫描</td><td>每 30 分钟</td>
 *       <td>晚半小时通知家属 —— 可接受，下一次扫描会补上</td>
 *       <td>可能重复推送提醒 —— 靠 {@code notify_sent} 挡住</td></tr>
 * </table>
 *
 * <p>两类任务对「重复」的容忍度都远高于「丢失」，这也是
 * {@link RedisLockUtil} 用「宁可不执行，也不并发执行」策略的原因。</p>
 *
 * <h3>为什么锁要写在任务方法里而不是切面里</h3>
 *
 * <p>锁的粒度与业务强相关：生成任务要锁一整天（防两个实例各插一半），
 * 漏服扫描只需要锁住「本轮」（下一轮 30 分钟后再抢锁即可）。
 * 放进统一注解会让这两者只能取同一个 TTL，要么过短失去意义，要么过长阻塞下一轮。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "nianglin.medication", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class MedicationScheduler {

    /** 生成任务的锁：TTL 10 分钟。全表生成本身是秒级，留足余量又不至于卡住跨天补跑 */
    private static final String LOCK_DAILY = "medication:daily";

    /** 漏服扫描的锁：TTL 5 分钟，小于扫描间隔（30 分钟），保证下一轮能正常抢到 */
    private static final String LOCK_MISSED = "medication:missed";

    private final MedicationService medicationService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 每天 07:00 生成当日服药任务。
     *
     * <p>07:00 这个时刻是产品定的：早于绝大多数人的服药时间点，
     * 又晚于凌晨的运维窗口。</p>
     */
    @Scheduled(cron = "${nianglin.medication.daily-generate-cron:0 0 7 * * ?}")
    public void generateDailyTasks() {
        LocalDate today = LocalDate.now();
        try (RedisLockUtil.Lock lock = RedisLockUtil.tryLock(
                redisTemplate, LOCK_DAILY, Duration.ofMinutes(10))) {
            if (!lock.acquired()) {
                return;
            }
            int created = medicationService.generateDailyTasks(today);
            log.info("每日服药任务生成任务结束 | date={} | 新增={}", today, created);
        } catch (Exception e) {
            // 这里必须自己接住异常：@Scheduled 方法抛出异常只会被记一条日志，
            // 但一个未被捕获的异常会打断当前调度轮次，让「下一次 30 分钟后再来」这件事
            // 变得不确定。任务本身是幂等的，失败后重跑即可
            log.error("每日服药任务生成失败 | date={} | {}", today, e.getMessage(), e);
        }
    }

    /**
     * 每 30 分钟扫描一次漏服。
     *
     * <p>{@code fixedRate} 而不是 {@code fixedDelay}：以「每 30 分钟一轮」为准，
     * 而不是「上一轮结束后再等 30 分钟」。漏服提醒的时效性比机器负载重要，
     * 而单轮被 {@code miss-scan-batch} 限制了上限，不会无限拖长。</p>
     */
    @Scheduled(
            fixedRateString = "${nianglin.medication.missed-scan-interval-ms:1800000}",
            initialDelayString = "${nianglin.medication.missed-scan-initial-delay-ms:300000}")
    public void scanMissedTasks() {
        try (RedisLockUtil.Lock lock = RedisLockUtil.tryLock(
                redisTemplate, LOCK_MISSED, Duration.ofMinutes(5))) {
            if (!lock.acquired()) {
                return;
            }
            int missed = medicationService.scanMissedTasks();
            if (missed > 0) {
                log.info("漏服扫描任务结束 | 本轮判定={} 条", missed);
            }
        } catch (Exception e) {
            log.error("漏服扫描失败 | {}", e.getMessage(), e);
        }
    }
}
