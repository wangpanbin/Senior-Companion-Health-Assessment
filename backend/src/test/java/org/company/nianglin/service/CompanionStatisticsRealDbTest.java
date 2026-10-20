package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.OrderRejectLog;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.OrderRejectLogMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.vo.ChartDataVO;
import org.company.nianglin.vo.CompanionIncomeVO;
import org.company.nianglin.vo.CompanionWorkbenchOverviewVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 陪诊员工作台的<b>真库</b>一致性测试（V3.3 · T5 · 真库层）。
 *
 * <h3>为什么单测之外还要真库这一层</h3>
 *
 * <p>Mockito 单测锁的是「Service 拿到这些数之后怎么算」，
 * 而聚合 SQL 本身对不对（{@code deleted = 0} 写没写、
 * {@code COALESCE(actual_fee, fee)} 有没有漏、{@code visit_time} 区间有没有写反）
 * 只有真跑一遍才知道。</p>
 *
 * <p>所以这里的断言方式很朴素也很硬：
 * <b>用 Java 逐条遍历订单算一遍，再和接口返回的数字比</b>。
 * 两套实现互为对照，任何一边漂移都会对不上。</p>
 *
 * <h3>「拒单不计入分母」的反向验证</h3>
 *
 * <p>种子数据里陪诊员 301 拒过 6 单（{@code order_reject_log}）。
 * 拒单<b>不会</b>把 {@code companion_id} 写进订单 —— 订单仍是 PENDING 等别人接，
 * 所以这 6 单根本不在该陪诊员的 {@code companion_order} 里。</p>
 *
 * <p>如果哪天有人把「拒单」也拉进完成率的分母，本测试会立刻红：
 * 分母会凭空多 6。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.3
 */
@SpringBootTest
@DisplayName("V3.3 真库一致性：聚合 SQL 与逐条 Java 统计必须一致")
class CompanionStatisticsRealDbTest {

    /** 种子里的陪诊员（有 6 条拒单记录） */
    private static final long COMPANION_ID = 301L;

    /** 覆盖全部种子订单的区间（种子的就诊时间从 2026-08 开始） */
    private static final String START_DATE = "2026-01-01";
    private static final String END_DATE = "2026-12-31";

    @Autowired
    private CompanionStatisticsService service;

    @Autowired
    private CompanionOrderMapper orderMapper;

    @Autowired
    private OrderRejectLogMapper rejectLogMapper;

    @BeforeEach
    void loginAsCompanion() {
        LoginUser loginUser = new LoginUser(COMPANION_ID, "comp001", RoleConstants.COMPANION, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("概览 · 聚合 SQL 与逐条 Java 统计一致（完成单数 / 时长 / 收入 / 完成率）")
    void overviewShouldMatchRowByRowAggregation() {
        DateRangeQuery query = query();
        CompanionWorkbenchOverviewVO vo = service.overview(query);

        List<CompanionOrder> orders = ordersOf(COMPANION_ID);

        long completed = orders.stream()
                .filter(o -> isDone(o.getStatus()))
                .count();
        long cancelled = orders.stream()
                .filter(o -> isCancelled(o.getStatus()))
                .count();
        long minutes = orders.stream()
                .filter(o -> isDone(o.getStatus()))
                .filter(o -> o.getStartTime() != null && o.getFinishTime() != null)
                .mapToLong(o -> java.time.Duration.between(o.getStartTime(), o.getFinishTime()).toMinutes())
                .sum();
        BigDecimal income = orders.stream()
                .filter(o -> isDone(o.getStatus()))
                .map(o -> o.getActualFee() != null ? o.getActualFee() : o.getFee())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals((int) completed, vo.getCompletedOrderCount(), "完成单数应与逐条统计一致");
        assertEquals((int) minutes, vo.getServiceDurationMinutes(), "服务时长应与逐条统计一致");
        assertEquals(money(income), vo.getPeriodIncome(), "区间收入应与逐条统计一致");
        assertEquals(ChartDataVO.percent(completed, completed + cancelled), vo.getCompletionRate(),
                "完成率应与逐条统计一致");
    }

    @Test
    @DisplayName("拒单不计入分母 · 陪诊员的拒单记录不进入完成率（种子里有 6 条拒单）")
    void rejectedOrdersShouldNotAffectCompletionRate() {
        Long rejected = rejectLogMapper.selectCount(Wrappers.<OrderRejectLog>lambdaQuery()
                .eq(OrderRejectLog::getCompanionId, COMPANION_ID));
        assertTrue(rejected != null && rejected > 0,
                "前提失效：种子里没有该陪诊员的拒单记录，本用例就失去意义了");

        List<CompanionOrder> orders = ordersOf(COMPANION_ID);
        long completed = orders.stream().filter(o -> isDone(o.getStatus())).count();
        long cancelled = orders.stream().filter(o -> isCancelled(o.getStatus())).count();

        // 逐条统计出来的分母里只有该陪诊员真正接过（并被取消）的单
        long denominator = completed + cancelled;
        assertTrue(denominator < completed + rejected,
                "拒单被算进了分母 —— 这正是本用例要防的事");

        assertEquals(ChartDataVO.percent(completed, denominator),
                service.overview(query()).getCompletionRate());
    }

    @Test
    @DisplayName("收入中心 · 待结算 + 已结算 = 实际收入，且订单金额 = SUM(fee)")
    void incomeShouldSplitConsistently() {
        CompanionIncomeVO vo = service.income(query());

        List<CompanionOrder> orders = ordersOf(COMPANION_ID).stream()
                .filter(o -> isDone(o.getStatus()))
                .toList();

        BigDecimal orderAmount = orders.stream()
                .map(o -> o.getFee() == null ? BigDecimal.ZERO : o.getFee())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal actual = orders.stream()
                .map(o -> o.getActualFee() != null ? o.getActualFee() : o.getFee())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal unsettled = orders.stream()
                .filter(o -> "UNPAID".equals(o.getPaymentStatus()))
                .map(o -> o.getActualFee() != null ? o.getActualFee() : o.getFee())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal settled = orders.stream()
                .filter(o -> "SETTLED".equals(o.getPaymentStatus()))
                .map(o -> o.getActualFee() != null ? o.getActualFee() : o.getFee())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(money(orderAmount), vo.getOrderAmount());
        assertEquals(money(actual), vo.getActualIncome());
        assertEquals(money(unsettled), vo.getUnsettledAmount());
        assertEquals(money(settled), vo.getSettledAmount());
        assertEquals(money(actual), money(unsettled.add(settled)),
                "待结算 + 已结算 必须等于实际收入");
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    /** 「已完成」= COMPLETED + REVIEWED（M10 起的铁律） */
    private static boolean isDone(String status) {
        return "COMPLETED".equals(status) || "REVIEWED".equals(status);
    }

    private static boolean isCancelled(String status) {
        return "CANCELLED".equals(status);
    }

    /** 该陪诊员在区间内的全部订单（含进行中与已取消） */
    private List<CompanionOrder> ordersOf(long companionId) {
        return orderMapper.selectList(Wrappers.<CompanionOrder>lambdaQuery()
                .eq(CompanionOrder::getCompanionId, companionId)
                .between(CompanionOrder::getVisitTime,
                        LocalDateTime.parse(START_DATE + "T00:00:00"),
                        LocalDateTime.parse(END_DATE + "T23:59:59")));
    }

    private static DateRangeQuery query() {
        DateRangeQuery query = new DateRangeQuery();
        query.setStartDate(START_DATE);
        query.setEndDate(END_DATE);
        return query;
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
