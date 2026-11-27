package org.company.nianglin.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.service.sms.SmsReminderService;
import org.company.nianglin.util.RedisLockUtil;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 短信重试扫描任务（V4 · P1-3）。
 *
 * <p>只处理 {@code sms_send_log} 里 {@code status=PENDING && next_retry_at <= now}
 * 的行，不参与漏服判定，也不改变 {@code MedicationScheduler} 的 30 分钟节奏与锁。</p>
 *
 * <p><b>锁独立</b>：{@code nianglin:lock:sms:retry}，与用药模块的
 * {@code medication:daily} / {@code medication:missed} 完全隔离。短信重试被跳过
 * 一次只是晚一分钟再发，不会影响漏服判定。</p>
 *
 * <p>静默时段内的待发送行由 {@link SmsReminderService#scanDueRetries()} 顺延到 08:00，
 * 这里只负责「按时醒来 + 只让一个实例执行」。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-3
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SmsRetryScheduler {

    /** 与用药调度完全独立的锁键 */
    private static final String LOCK_SMS_RETRY = "sms:retry";

    private final SmsReminderService smsReminderService;
    private final StringRedisTemplate redisTemplate;

    /**
     * 扫描节奏与首次延迟是两件事，必须用两个键。
     *
     * <p>此前两者共用 {@code nianglin.sms.retry.scan-interval-ms}：运维把间隔调成 30 分钟，
     * 「启动后多久开始跑」也跟着推迟 30 分钟 —— 静默时段攒下的待发短信会在重启后
     * 白白多积压半小时，而日志上完全看不出是配置耦合导致的。</p>
     *
     * <p>{@code initial-delay-ms} 缺省 30 秒：足够让容器与连接池就绪，
     * 又不至于把重试拖成「下一次部署才发」。</p>
     */
    @Scheduled(
            fixedDelayString = "${nianglin.sms.retry.scan-interval-ms:60000}",
            initialDelayString = "${nianglin.sms.retry.initial-delay-ms:30000}")
    public void scanRetries() {
        try (RedisLockUtil.Lock lock = RedisLockUtil.tryLock(
                redisTemplate, LOCK_SMS_RETRY, Duration.ofMinutes(2))) {
            if (!lock.acquired()) {
                return;
            }
            int handled = smsReminderService.scanDueRetries();
            if (handled > 0) {
                log.info("短信重试扫描结束 | 本轮处理={} 条", handled);
            }
        } catch (Exception e) {
            // @Scheduled 抛异常会打断后续轮次，必须自己接住；扫描本身是幂等的
            log.error("短信重试扫描失败 | {}", e.getMessage(), e);
        }
    }
}
