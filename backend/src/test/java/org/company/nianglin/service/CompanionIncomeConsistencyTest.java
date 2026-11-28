package org.company.nianglin.service;

import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.mapper.CompanionStatisticsMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.CompanionStatisticsServiceImpl;
import org.company.nianglin.vo.CompanionIncomeVO;
import org.company.nianglin.vo.CompanionWorkbenchOverviewVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 陪诊员「区间收入」两个入口的一致性回归（收口 B · P2-3）。
 *
 * <p>{@code periodIncome} 与 {@code actualIncome} 公式逐字相同，规范收敛为一个口径、
 * 另一个作为别名保留。本测试锁住「两个入口不该分叉」这一行为，
 * 若要改金额公式只能改一处；指标字典的别名声明由 {@code gate_check --only metric-dict} 覆盖。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("陪诊员区间收入：两入口同源（P2-3）")
class CompanionIncomeConsistencyTest {

    @Mock
    private CompanionStatisticsMapper companionStatisticsMapper;

    private CompanionStatisticsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CompanionStatisticsServiceImpl(companionStatisticsMapper);
        LoginUser loginUser = new LoginUser(301L, "companion301", RoleConstants.COMPANION, 0,
                "jti-p2-3", System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("工作台 periodIncome 与收入页 actualIncome 应相等（同一取数口径）")
    void periodIncomeShouldEqualActualIncome() {
        // 两条完全不同的调用路径，但 money() 都取 selectOrderStats 的同一个 income 列
        given(companionStatisticsMapper.selectOrderStats(any(), any(), any()))
                .willReturn(Map.of("income", new BigDecimal("3680.00")));
        given(companionStatisticsMapper.selectProfileScore(any())).willReturn(new BigDecimal("4.80"));
        given(companionStatisticsMapper.selectSettlementStats(any(), any(), any())).willReturn(List.of());
        given(companionStatisticsMapper.selectFeeItemTotals(any(), any(), any())).willReturn(List.of());

        CompanionWorkbenchOverviewVO overview = service.overview(new DateRangeQuery());
        CompanionIncomeVO income = service.income(new DateRangeQuery());

        assertEquals(overview.getPeriodIncome(), income.getActualIncome(),
                "工作台与收入页必须显示同一个数，别名不应分叉");
    }

    @Test
    @DisplayName("unsettled + settled = actual（对账等式有行为保证）")
    void unsettledPlusSettledShouldEqualActual() {
        given(companionStatisticsMapper.selectOrderStats(any(), any(), any()))
                .willReturn(Map.of("income", new BigDecimal("3680.00")));
        given(companionStatisticsMapper.selectSettlementStats(any(), any(), any())).willReturn(List.of(
                Map.of("paymentStatus", "UNPAID", "amount", new BigDecimal("800.00")),
                Map.of("paymentStatus", "SETTLED", "amount", new BigDecimal("2880.00"))));
        given(companionStatisticsMapper.selectFeeItemTotals(any(), any(), any())).willReturn(List.of());

        CompanionIncomeVO income = service.income(new DateRangeQuery());

        assertEquals("3680.00", new BigDecimal(income.getUnsettledAmount())
                .add(new BigDecimal(income.getSettledAmount())).toPlainString(),
                "拆分金额必须自洽（独立真源为夹具里写入的 800 + 2880）");
        assertEquals(income.getActualIncome(), new BigDecimal(income.getUnsettledAmount())
                .add(new BigDecimal(income.getSettledAmount())).toPlainString());
    }
}
