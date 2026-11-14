package org.company.nianglin.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.service.ExamPrepReminderService;
import org.company.nianglin.util.RedisLockUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 检查准备提醒定时任务（V5 · B）。
 *
 * <p>沿用 {@link MedicationScheduler} 定下的三件套：<b>每天 09:00</b> 扫描明天的就诊订单、
 * {@link RedisLockUtil} 保证多实例只有一个真执行、异常在方法内吞掉
 * （{@code @Scheduled} 抛异常会打断当前调度轮次）。</p>
 *
 * <p>为什么是 09:00：早于绝大多数人的出门时间，又留出「今天还来得及准备
 * （空腹、带材料）」的余量 —— 提醒的是<b>明天</b>的就诊，不是当天临时抱佛脚。</p>
 *
 * @author 银龄伴诊团队
 * @since V5
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "nianglin.exam-prep", name = "scheduler-enabled",
        havingValue = "true", matchIfMissing = true)
public class ExamPrepScheduler {

    /** 锁 TTL 10 分钟：扫描本身是秒级，留足余量又不至于卡住明天这一轮 */
    private static final String LOCK_EXAM_PREP = "exam-prep:remind-scan";

    private final ExamPrepReminderService examPrepReminderService;
    private final StringRedisTemplate redisTemplate;

    /** 每天 09:00 扫描明天有就诊安排的订单 */
    @Scheduled(cron = "${nianglin.exam-prep.remind-cron:0 0 9 * * ?}")
    public void scanTomorrowOrders() {
        try (RedisLockUtil.Lock lock = RedisLockUtil.tryLock(
                redisTemplate, LOCK_EXAM_PREP, Duration.ofMinutes(10))) {
            if (!lock.acquired()) {
                return;
            }
            int sent = examPrepReminderService.scanTomorrowOrders();
            if (sent > 0) {
                log.info("检查准备提醒任务结束 | 本轮提醒={} 单", sent);
            }
        } catch (Exception e) {
            log.error("检查准备提醒扫描失败 | {}", e.getMessage(), e);
        }
    }
}
