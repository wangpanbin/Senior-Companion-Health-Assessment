package org.company.nianglin.service;

import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.mapper.OperationsMapper;
import org.company.nianglin.service.impl.OperationsServiceImpl;
import org.company.nianglin.service.support.metrics.MetricCalculatorRegistry;
import org.company.nianglin.vo.FulfillmentSummaryVO;
import org.company.nianglin.vo.OperationsExceptionItemVO;
import org.company.nianglin.vo.OperationsExceptionVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 异常中心与 V3.1 履约风险中心的<b>同源</b>校验（V3.5 · T6 · 本版最重要的一条）。
 *
 * <h3>为什么这条要单独一个测试类</h3>
 *
 * <p>决策 5.1：异常中心聚合 V3.1 的风险中心，<b>不另建一套风险判定</b>。</p>
 *
 * <p>「另建一套」这个错误特别难靠肉眼发现：两处代码都能跑、数字都像那么回事，
 * 差别只在「口径」上 —— 风险中心只统计 {@code status = PENDING} 的订单，
 * 而另写一套的人很可能顺手把 {@code ACCEPTED} 也算进去。
 * 症状是：管理员在风险中心看到 3 单需人工介入，在驾驶舱看到 5 单，
 * 两边都对不上账，而且没人能说清哪个是真的。</p>
 *
 * <h3>本类怎么把它锁死</h3>
 *
 * <ol>
 *   <li><b>正向</b>：异常中心的两个数量 == {@code AdminService#fulfillmentSummary()} 的
 *       {@code manualRequired} 与 {@code warning + urgent}；</li>
 *   <li><b>取数路径</b>：断言异常中心<b>确实调用了</b> {@code fulfillmentSummary()} ——
 *       这一条把「复用」从约定变成可验证的事实；</li>
 *   <li><b>反向</b>：让风险中心返回一个「本地根本算不出来」的数（例如 99），
 *       断言异常中心照单全收。
 *       <b>任何改成「另算一套」的实现都会在这里得到 0 而变红。</b></li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V3.5
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V3.5 异常中心与 V3.1 风险中心必须同源（决策 5.1）")
class OperationsExceptionParityTest {

    @Mock
    private OperationsMapper mapper;

    @Mock
    private AdminService adminService;

    private final OperationsProperties properties = new OperationsProperties();

    private OperationsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OperationsServiceImpl(mapper, adminService, properties,
                new MetricCalculatorRegistry(List.of()));
        when(mapper.selectCompanionComplaintRates(any(), any())).thenReturn(List.of());
        when(mapper.selectMissedMedicationCount(any(), any())).thenReturn(0);
        when(mapper.selectRecentMissedOrders(any(), any(), anyInt())).thenReturn(List.of());
    }

    /* ================================================================== */
    /* 1 · 正向：数量必须相等                                               */
    /* ================================================================== */

    @Test
    @DisplayName("需人工介入订单数 == 风险中心的 manualRequired")
    void manualRequiredShouldEqualFulfillmentSummary() {
        when(adminService.fulfillmentSummary()).thenReturn(summary(3, 1, 5, 10));

        OperationsExceptionVO vo = service.exceptions(new DateRangeQuery());

        assertEquals(3, countOf(vo, "MANUAL_REQUIRED"));
    }

    @Test
    @DisplayName("超匹配预警时间订单数 == 风险中心的 warning + urgent")
    void matchWarningShouldEqualWarningPlusUrgent() {
        when(adminService.fulfillmentSummary()).thenReturn(summary(3, 1, 5, 10));

        OperationsExceptionVO vo = service.exceptions(new DateRangeQuery());

        // 5 (WARNING) + 1 (URGENT) = 6
        assertEquals(6, countOf(vo, "MATCH_WARNING"));
    }

    /* ================================================================== */
    /* 2 · 取数路径：确实复用了风险中心                                     */
    /* ================================================================== */

    @Test
    @DisplayName("异常中心确实调用了 AdminService#fulfillmentSummary（复用是可验证的事实）")
    void exceptionsShouldConsultFulfillmentSummary() {
        when(adminService.fulfillmentSummary()).thenReturn(summary(2, 0, 0, 0));

        service.exceptions(new DateRangeQuery());

        verify(adminService, atLeastOnce()).fulfillmentSummary();
    }

    /* ================================================================== */
    /* 3 · 反向验证：改成「另算一套」立刻变红                               */
    /* ================================================================== */

    @Test
    @DisplayName("反向验证 · 风险中心返回什么，异常中心就报什么（另算一套会得到 0 而变红）")
    void exceptionsShouldFollowFulfillmentSummaryVerbatim() {
        // 99 是一个本地任何算法都算不出来的数：mapper 一个订单都没给，
        // 任何「自己数一遍 PENDING 订单」的实现都会返回 0
        when(adminService.fulfillmentSummary()).thenReturn(summary(99, 40, 50, 0));

        OperationsExceptionVO vo = service.exceptions(new DateRangeQuery());

        assertEquals(99, countOf(vo, "MANUAL_REQUIRED"));
        assertEquals(90, countOf(vo, "MATCH_WARNING"));
    }

    @Test
    @DisplayName("反向验证 · 风险中心的数变了，异常中心跟着变（不是自己算的）")
    void exceptionsShouldFollowChangedFulfillmentSummary() {
        when(adminService.fulfillmentSummary()).thenReturn(summary(1, 0, 0, 0));
        assertEquals(1, countOf(service.exceptions(new DateRangeQuery()), "MANUAL_REQUIRED"));

        when(adminService.fulfillmentSummary()).thenReturn(summary(7, 2, 3, 0));
        OperationsExceptionVO vo = service.exceptions(new DateRangeQuery());

        assertEquals(7, countOf(vo, "MANUAL_REQUIRED"));
        assertEquals(5, countOf(vo, "MATCH_WARNING"));
    }

    @Test
    @DisplayName("反向验证 · 异常中心不得自己查订单状态（风险判定只允许有一个来源）")
    void exceptionsShouldNotComputeRiskCountsOnItsOwn() {
        when(adminService.fulfillmentSummary()).thenReturn(summary(4, 1, 2, 0));

        service.exceptions(new DateRangeQuery());

        // 风险数量如果由异常中心自己算，必然要查订单表；这里断言它没有这么做
        verify(mapper, org.mockito.Mockito.never()).selectOrderStats(any(), any(), anyInt(), any(), any());
        verify(mapper, org.mockito.Mockito.never()).selectDailyOrderTrend(any(), any());
    }

    @Test
    @DisplayName("同源 · 两项数量与风险中心一致，且 total 是四项之和")
    void exceptionTotalShouldBeSumOfFourItems() {
        when(adminService.fulfillmentSummary()).thenReturn(summary(3, 1, 5, 10));

        OperationsExceptionVO vo = service.exceptions(new DateRangeQuery());

        FulfillmentSummaryVO summary = adminService.fulfillmentSummary();
        assertEquals(summary.getManualRequired(), countOf(vo, "MANUAL_REQUIRED"));
        assertEquals(summary.getWarning() + summary.getUrgent(), countOf(vo, "MATCH_WARNING"));
        assertEquals(4, vo.getItems().size());
        int sum = vo.getItems().stream().mapToInt(OperationsExceptionItemVO::getCount).sum();
        assertEquals(sum, vo.getTotal());
        assertTrue(vo.getTotal() > 0);
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    private static FulfillmentSummaryVO summary(int manualRequired, int urgent, int warning, int normal) {
        return new FulfillmentSummaryVO()
                .setManualRequired(manualRequired)
                .setUrgent(urgent)
                .setWarning(warning)
                .setNormal(normal);
    }

    private static int countOf(OperationsExceptionVO vo, String type) {
        for (OperationsExceptionItemVO item : vo.getItems()) {
            if (type.equals(item.getType())) {
                return item.getCount();
            }
        }
        throw new AssertionError("异常中心没有返回类型：" + type);
    }
}
