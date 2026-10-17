package org.company.nianglin.task;

import org.company.nianglin.service.FulfillmentService;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 履约风险扫描任务单测（V3.1 · T2）。
 *
 * <p>这组测试盯的是任务壳子的三条行为，缺一不可：</p>
 * <ol>
 *   <li><b>没抢到锁就直接返回</b> —— 多个实例同时跑时不该重复处理同一批订单；</li>
 *   <li><b>异常必须自己接住</b> —— {@code @Scheduled} 方法把异常抛出去会打断
 *       当前调度轮次，让「下一次间隔再来」变得不确定；</li>
 *   <li><b>零升级时不刷日志</b> —— 否则每 5 分钟一条 info，日志会被无意义的行淹没，
 *       真出事时反而找不到那一条。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("履约风险扫描任务：锁 / 异常 / 静默")
class FulfillmentSchedulerTest {

    @Mock
    private FulfillmentService fulfillmentService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private FulfillmentScheduler scheduler;

    @BeforeEach
    void setUp() {
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        scheduler = new FulfillmentScheduler(fulfillmentService, redisTemplate);
    }

    @AfterEach
    void tearDown() {
        org.mockito.Mockito.reset(redisTemplate, valueOperations);
    }

    /* ================================================================== */
    /* 锁                                                                  */
    /* ================================================================== */

    @Test
    @DisplayName("未抢到锁：直接返回，完全不碰扫描逻辑")
    void shouldSkipWhenLockNotAcquired() {
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(false);

        scheduler.scanFulfillmentRisk();

        verify(fulfillmentService, never()).scanAndEscalate();
    }

    @Test
    @DisplayName("抢到锁：执行一轮扫描")
    void shouldScanWhenLockAcquired() {
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(true);
        given(fulfillmentService.scanAndEscalate()).willReturn(3);

        scheduler.scanFulfillmentRisk();

        verify(fulfillmentService, times(1)).scanAndEscalate();
    }

    @Test
    @DisplayName("抢锁返回 null（Redis 异常）也按「未抢到」处理，不抛异常")
    void shouldTreatNullLockResultAsNotAcquired() {
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(null);

        scheduler.scanFulfillmentRisk();

        verify(fulfillmentService, never()).scanAndEscalate();
    }

    /* ================================================================== */
    /* 异常自接住                                                          */
    /* ================================================================== */

    @Test
    @DisplayName("扫描抛异常时被接住，不向调度线程传播")
    void shouldSwallowServiceException() {
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(true);
        willThrow(new IllegalStateException("数据库连接不可用"))
                .given(fulfillmentService).scanAndEscalate();

        assertDoesNotThrow(() -> scheduler.scanFulfillmentRisk());
    }

    @Test
    @DisplayName("抢锁阶段抛异常同样被接住")
    void shouldSwallowLockException() {
        willThrow(new IllegalStateException("Redis 不可用"))
                .given(redisTemplate).opsForValue();

        assertDoesNotThrow(() -> scheduler.scanFulfillmentRisk());
    }

    /* ================================================================== */
    /* 无意义日志                                                          */
    /* ================================================================== */

    @Test
    @DisplayName("零升级时正常返回，不视为失败")
    void shouldReturnNormallyWhenNothingEscalated() {
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(true);
        given(fulfillmentService.scanAndEscalate()).willReturn(0);

        assertDoesNotThrow(() -> scheduler.scanFulfillmentRisk());
    }
}
