package org.company.nianglin.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.service.impl.CompanionCertReminderService;
import org.company.nianglin.util.RedisLockUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 健康证到期扫描任务（V5 · C1）。
 *
 * <p>沿用 {@link MedicationScheduler} 定下的三件套：<b>每天 09:00</b> 跑一轮、
 * {@link RedisLockUtil} 保证多实例只有一个真执行、异常在方法内吞掉
 * （{@code @Scheduled} 抛异常会打断当前调度轮次，让「明天再来」变得不确定）。</p>
 *
 * <p>漏执行的代价是晚一天提醒 —— 可接受；重复执行的代价是重复打扰 ——
 * 除锁之外还有服务侧的幂等键兜底，两层都符合「宁可不执行，也不重复执行」。</p>
 *
 * @author 银龄伴诊团队
 * @since V5
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "nianglin.companion", name = "cert-scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class CompanionCertExpireScheduler {

    /** 锁 TTL 10 分钟：扫描本身是秒级，留足余量又不至于卡住明天这一轮 */
    private static final String LOCK_CERT_EXPIRE = "companion:cert-expire";

    private final CompanionCertReminderService companionCertReminderService;
    private final StringRedisTemplate redisTemplate;

    /** 每天 09:00 扫描：晚于生成当日任务（07:00），早于绝大多数就诊时段 */
    @Scheduled(cron = "${nianglin.companion.cert-expire-cron:0 0 9 * * ?}")
    public void scanExpiringCerts() {
        try (RedisLockUtil.Lock lock = RedisLockUtil.tryLock(
                redisTemplate, LOCK_CERT_EXPIRE, Duration.ofMinutes(10))) {
            if (!lock.acquired()) {
                return;
            }
            int sent = companionCertReminderService.scanExpiring();
            if (sent > 0) {
                log.info("健康证到期扫描任务结束 | 本轮提醒={} 条", sent);
            }
        } catch (Exception e) {
            log.error("健康证到期扫描失败 | {}", e.getMessage(), e);
        }
    }
}
