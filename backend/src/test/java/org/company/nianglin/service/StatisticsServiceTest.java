package org.company.nianglin.service;

import org.company.nianglin.common.ResultCode;
import org.company.nianglin.dto.AdminOrderQuery;
import org.company.nianglin.dto.AdminUserQuery;
import org.company.nianglin.dto.StatisticsQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.AdminReadMapper;
import org.company.nianglin.mapper.OrderReadMapper;
import org.company.nianglin.mapper.StatisticsMapper;
import org.company.nianglin.service.impl.StatisticsServiceImpl;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.StatisticsOverviewVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 数据统计与导出服务单测（M10）。
 *
 * <p>M10 的代码几乎没有分支，风险集中在两处<b>边界</b>上 ——
 * 而这两处一旦失守，代价都不小：</p>
 *
 * <ol>
 *   <li><b>统计区间</b>。起止颠倒时 SQL 不会报错，只会<b>静默返回空数据</b> ——
 *       用户看到的是「本月订单 0 笔」，而不是一个错误提示。
 *       所以区间校验必须在进入 SQL 之前收口，且默认区间要落在「最近 30 天」而不是
 *       从 1970 年开始。</li>
 *   <li><b>导出行数上限</b>。导出是唯一一个会把全表拉进内存并生成文件的接口，
 *       没有上限就等于给了一个「一次点爆内存」的入口。
 *       上限的判定要发生在<b>写 Excel 之前</b> —— 先写再判等于白写。</li>
 * </ol>
 *
 * <p>本类还顺带把「多查一行」这个实现细节钉住：
 * {@code adminService.queryOrdersForExport(query, limit)} 内部用
 * {@code LIMIT limit + 1} 一次查询同时完成「取数据」与「判断是否超限」，
 * 不必先 {@code COUNT} 一遍。用例断言传给 SQL 的 limit 就是配置值本身。</p>
 *
 * @author 银龄伴诊团队
 * @since M10
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("统计服务：区间收口 / 默认窗口 / 导出上限先判后写")
class StatisticsServiceTest {

    /** 与代码默认值一致；单测手动注入，避免 @Value 不生效导致上限为 0 */
    private static final int MAX_EXPORT_ROWS = 10000;

    @Mock
    private StatisticsMapper statisticsMapper;

    @Mock
    private AdminReadMapper adminReadMapper;

    @Mock
    private OrderReadMapper orderReadMapper;

    @Mock
    private AdminService adminService;

    @Mock
    private ExportService exportService;

    @Mock
    private UserNameResolver userNameResolver;

    private StatisticsServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new StatisticsServiceImpl(statisticsMapper, adminReadMapper, orderReadMapper,
                adminService, exportService, userNameResolver);
        ReflectionTestUtils.setField(service, "maxExportRows", MAX_EXPORT_ROWS);
    }

    /* ================================================================== */
    /* 1 · 统计区间                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("统计区间 · 开始日期晚于结束日期 → 9002（不校验会静默返回空数据）")
    void invertedRangeShouldBeRejected() {
        StatisticsQuery query = range("2026-09-15", "2026-09-01");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.overview(query));

        assertEquals(ResultCode.STAT_RANGE_INVALID.getCode(), ex.getCode());
        verify(statisticsMapper, never()).selectOrderCountsByStatus(any(), any());
    }

    @Test
    @DisplayName("统计区间 · 跨度超过 366 天 → 9002（否则统计接口就成了全表扫描入口）")
    void tooWideRangeShouldBeRejected() {
        StatisticsQuery query = range("2024-01-01", "2026-09-16");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.overview(query));

        assertEquals(ResultCode.STAT_RANGE_INVALID.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("统计区间 · 不传日期时默认取最近 30 天（不是从 1970 年开始）")
    void defaultRangeShouldCoverLastThirtyDays() {
        given(statisticsMapper.selectNewUserCount(any(), any())).willReturn(0);

        service.overview(new StatisticsQuery());

        ArgumentCaptor<LocalDateTime> startCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(statisticsMapper).selectNewUserCount(startCaptor.capture(), endCaptor.capture());

        LocalDateTime start = startCaptor.getValue();
        LocalDateTime end = endCaptor.getValue();
        assertEquals(LocalDate.now(), end.toLocalDate(), "默认结束日应是今天");
        assertEquals(29, ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()),
                "默认窗口是 30 天（含首含尾），即起止相差 29 天");
        assertTrue(start.isBefore(end));
    }

    @Test
    @DisplayName("统计区间 · 只传开始日期时，结束日补成今天（而不是把开始日当结束）")
    void missingEndDateShouldFallBackToToday() {
        given(statisticsMapper.selectNewUserCount(any(), any())).willReturn(0);
        StatisticsQuery query = new StatisticsQuery();
        query.setStartDate(LocalDate.now().minusDays(6).toString());

        service.overview(query);

        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(statisticsMapper).selectNewUserCount(any(), endCaptor.capture());
        assertEquals(LocalDate.now(), endCaptor.getValue().toLocalDate());
    }

    @Test
    @DisplayName("统计总览 · 无数据时各计数为 0，不抛异常（新部署的第一天就是这个状态）")
    void overviewShouldTolerateEmptyDatabase() {
        StatisticsOverviewVO vo = service.overview(new StatisticsQuery());

        assertNotNull(vo);
        assertEquals(0, vo.getOrder().getTotalCount());
        assertEquals(0, vo.getUser().getTotalCount());
        assertEquals(0, vo.getMedication().getTaskTotalCount());
    }

    @Test
    @DisplayName("统计总览 · 增长率的分母是「区间开始前的用户数」，不是用户总数")
    void growthRateShouldUsePreRangeBase() {
        given(statisticsMapper.selectUserTotalCount()).willReturn(100);
        given(statisticsMapper.selectNewUserCount(any(), any())).willReturn(25);

        StatisticsOverviewVO vo = service.overview(new StatisticsQuery());

        assertEquals(100, vo.getUser().getTotalCount());
        assertEquals(25, vo.getUser().getNewCount());
        // 25 / (100 - 25) = 33.33%，用总数当分母会得到 25.00%
        assertEquals("33.33%", vo.getUser().getGrowthRate());
    }

    /* ================================================================== */
    /* 2 · 导出上限：必须「先判后写」                                        */
    /* ================================================================== */

    @Test
    @DisplayName("导出订单 · 结果超过上限 → 9001，且一个字都不许写进 response")
    void exportOrdersShouldRejectWhenOverLimit() {
        given(adminService.queryOrdersForExport(any(), anyInt()))
                .willReturn(orders(MAX_EXPORT_ROWS + 1));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.exportOrders(new AdminOrderQuery(), new MockHttpServletResponse()));

        assertEquals(ResultCode.EXPORT_LIMIT_EXCEEDED.getCode(), ex.getCode());
        // 先写后判等于白写：文件已经开始下载，用户却拿到一句「超限」
        verify(exportService, never()).writeExcel(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("导出订单 · 查库时多取一行（LIMIT limit+1）用于判断超限，不必先 COUNT")
    void exportOrdersShouldProbeWithLimitPlusOne() {
        given(adminService.queryOrdersForExport(any(), anyInt())).willReturn(List.of());

        service.exportOrders(new AdminOrderQuery(), new MockHttpServletResponse());

        // 断言传给 SQL 的 limit 就是配置值本身；实现方内部负责 +1
        verify(adminService).queryOrdersForExport(any(), org.mockito.ArgumentMatchers.eq(MAX_EXPORT_ROWS));
        verify(exportService).writeExcel(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("导出订单 · 恰好等于上限 → 放行（边界是「超过」而不是「达到」）")
    void exportOrdersShouldAllowExactlyAtLimit() {
        given(adminService.queryOrdersForExport(any(), anyInt())).willReturn(orders(MAX_EXPORT_ROWS));

        service.exportOrders(new AdminOrderQuery(), new MockHttpServletResponse());

        verify(exportService).writeExcel(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("导出用户 · 结果超过上限 → 9001")
    void exportUsersShouldRejectWhenOverLimit() {
        given(adminService.queryUsersForExport(any(), anyInt()))
                .willReturn(java.util.Collections.nCopies(MAX_EXPORT_ROWS + 1, new org.company.nianglin.entity.SysUser()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.exportUsers(new AdminUserQuery(), new MockHttpServletResponse()));

        assertEquals(ResultCode.EXPORT_LIMIT_EXCEEDED.getCode(), ex.getCode());
        verify(exportService, never()).writeExcel(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("导出上限 · 上下限随配置变化（不是写死的 10000）")
    void exportLimitShouldFollowConfiguration() {
        ReflectionTestUtils.setField(service, "maxExportRows", 2);
        given(adminService.queryOrdersForExport(any(), anyInt())).willReturn(orders(3));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.exportOrders(new AdminOrderQuery(), new MockHttpServletResponse()));

        assertEquals(ResultCode.EXPORT_LIMIT_EXCEEDED.getCode(), ex.getCode());
    }

    /* ================================================================== */

    private StatisticsQuery range(String start, String end) {
        StatisticsQuery query = new StatisticsQuery();
        query.setStartDate(start);
        query.setEndDate(end);
        return query;
    }

    private List<CompanionOrder> orders(int count) {
        java.util.List<CompanionOrder> list = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            CompanionOrder order = new CompanionOrder();
            order.setId(1000L + i);
            order.setOrderNo("NL2026091600" + String.format("%04d", i));
            order.setStatus("COMPLETED");
            list.add(order);
        }
        return list;
    }
}
