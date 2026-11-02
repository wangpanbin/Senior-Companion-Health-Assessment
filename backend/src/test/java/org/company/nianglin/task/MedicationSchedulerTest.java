package org.company.nianglin.task;

import org.company.nianglin.service.MedicationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 用药定时任务单测（V4 · P0-3，对应 BUG_LIST L5）。
 *
 * <p>与 {@link FulfillmentSchedulerTest} 同一模板，盯任务壳子的三条行为：</p>
 * <ol>
 *   <li><b>没抢到锁就直接返回</b> —— 多实例并发时不重复生成任务 / 重复推送；</li>
 *   <li><b>异常必须自己接住</b> —— {@code @Scheduled} 方法抛出异常会打断
 *       当前调度轮次；两个任务都幂等，失败后下一轮重跑即可；</li>
 *   <li><b>零结果时正常返回</b> —— 漏服扫描没扫到东西不是失败。</li>
 * </ol>
 *
 * <p>{@code generateDailyTasks} 与 {@code scanMissedTasks} 各覆盖一遍，
 * 因为两者的锁 TTL 与失败代价刻意不同（见 MedicationScheduler 类注释），
 * 不能只测一个就推定另一个也对。</p>
 *
 * @author 银龄伴诊团队
 * @since V4
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("用药定时任务：锁 / 异常 / 静默（生成 + 漏服扫描）")
class MedicationSchedulerTest {

    @Mock
    private MedicationService medicationService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private MedicationScheduler scheduler;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        scheduler = new MedicationScheduler(medicationService, redisTemplate);
    }

    @AfterEach
    void tearDown() {
        org.mockito.Mockito.reset(redisTemplate, valueOperations);
    }

    /* ================================================================== */
    /* 每日生成：锁                                                          */
    /* ================================================================== */

    @Test
    @DisplayName("生成任务-未抢到锁：直接返回，不生成任何任务")
    void shouldSkipGenerateWhenLockNotAcquired() {
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(false);

        scheduler.generateDailyTasks();

        verify(medicationService, never()).generateDailyTasks(any(LocalDate.class));
    }

    @Test
    @DisplayName("生成任务-抢到锁：生成当日任务")
    void shouldGenerateWhenLockAcquired() {
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(true);
        given(medicationService.generateDailyTasks(any(LocalDate.class))).willReturn(12);

        scheduler.generateDailyTasks();

        verify(medicationService, times(1)).generateDailyTasks(any(LocalDate.class));
    }

    @Test
    @DisplayName("生成任务-抢锁返回 null（Redis 异常）按「未抢到」处理，不抛异常")
    void shouldTreatNullLockAsNotAcquiredOnGenerate() {
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(null);

        scheduler.generateDailyTasks();

        verify(medicationService, never()).generateDailyTasks(any(LocalDate.class));
    }

    /* ================================================================== */
    /* 每日生成：异常自接住                                                  */
    /* ================================================================== */

    @Test
    @DisplayName("生成任务-Service 抛异常时被接住，不向调度线程传播")
    void shouldSwallowServiceExceptionOnGenerate() {
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(true);
        willThrow(new IllegalStateException("数据库连接不可用"))
                .given(medicationService).generateDailyTasks(any(LocalDate.class));

        assertDoesNotThrow(() -> scheduler.generateDailyTasks());
    }

    @Test
    @DisplayName("生成任务-抢锁阶段抛异常同样被接住")
    void shouldSwallowLockExceptionOnGenerate() {
        willThrow(new IllegalStateException("Redis 不可用"))
                .given(redisTemplate).opsForValue();

        assertDoesNotThrow(() -> scheduler.generateDailyTasks());
    }

    /* ================================================================== */
    /* 漏服扫描：锁                                                          */
    /* ================================================================== */

    @Test
    @DisplayName("漏服扫描-未抢到锁：直接返回，不碰扫描逻辑")
    void shouldSkipScanWhenLockNotAcquired() {
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(false);

        scheduler.scanMissedTasks();

        verify(medicationService, never()).scanMissedTasks();
    }

    @Test
    @DisplayName("漏服扫描-抢到锁：执行一轮扫描")
    void shouldScanWhenLockAcquired() {
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(true);
        given(medicationService.scanMissedTasks()).willReturn(3);

        scheduler.scanMissedTasks();

        verify(medicationService, times(1)).scanMissedTasks();
    }

    @Test
    @DisplayName("漏服扫描-抢锁返回 null 按「未抢到」处理")
    void shouldTreatNullLockAsNotAcquiredOnScan() {
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(null);

        scheduler.scanMissedTasks();

        verify(medicationService, never()).scanMissedTasks();
    }

    /* ================================================================== */
    /* 漏服扫描：异常自接住与零结果                                          */
    /* ================================================================== */

    @Test
    @DisplayName("漏服扫描-Service 抛异常时被接住")
    void shouldSwallowServiceExceptionOnScan() {
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(true);
        willThrow(new IllegalStateException("数据库连接不可用"))
                .given(medicationService).scanMissedTasks();

        assertDoesNotThrow(() -> scheduler.scanMissedTasks());
    }

    @Test
    @DisplayName("漏服扫描-零漏服时正常返回，不视为失败")
    void shouldReturnNormallyWhenNothingMissed() {
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(true);
        given(medicationService.scanMissedTasks()).willReturn(0);

        assertDoesNotThrow(() -> scheduler.scanMissedTasks());
    }
}
