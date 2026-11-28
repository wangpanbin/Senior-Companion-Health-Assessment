package org.company.nianglin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.MatchingProperties;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.PaymentStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ArbitrateDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.AdminOperLogMapper;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.OrderStatusLogMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.AdminServiceImpl;
import org.company.nianglin.service.impl.OrderServiceImpl;
import org.company.nianglin.service.support.OperLogRecorderImpl;
import org.company.nianglin.service.support.OrderTransitionService;
import org.company.nianglin.service.support.OrderTransitionServiceImpl;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.support.MybatisLambdaCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;

import java.util.stream.Stream;

/**
 * P0-5 · 已结算订单不可强制取消。
 *
 * <p>一张已完成的订单被管理员核定结算（线下已付款）之后又被强制取消：
 * 订单状态变成「已取消」，结算状态仍是「已结算」。而结算台账只收「已完成 / 已评价」的订单，
 * 于是这张单从台账与导出里<b>凭空消失</b> —— 钱已经付出去了，对账时这笔差额无法解释。</p>
 *
 * <p>本类锁定「已结算 → 强制取消 → 被拒」这条约束，并保护「未结算可取消」「已结算可强制完成」
 * 两条不被误伤的路径。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P0-5 · 已结算订单不可强制取消")
class OrderForceTerminalSettledTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long ORDER_ID = 2001L;
    private static final Long FAMILY_ID = 101L;
    private static final Long COMPANION_ID = 301L;

    @Mock
    private CompanionOrderMapper orderMapper;

    @Mock
    private OrderStatusLogMapper statusLogMapper;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private AdminOperLogMapper operLogMapper;

    @Mock
    private MessageService messageService;

    @Mock
    private UserNameResolver userNameResolver;

    @Mock
    private OrderFeeItemService orderFeeItemService;

    private AdminServiceImpl adminService;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        loginAsAdmin();

        OrderTransitionService transitionService = new OrderTransitionServiceImpl(
                orderMapper, statusLogMapper, companionProfileMapper, sysUserMapper);
        OrderServiceImpl orderService = new OrderServiceImpl(orderMapper, statusLogMapper, null,
                null, companionProfileMapper, sysUserMapper, null,
                null, messageService, null, new ObjectMapper(),
                transitionService, orderFeeItemService, null, null,
                null, new MatchingProperties(), null);

        adminService = new AdminServiceImpl(null, companionProfileMapper, sysUserMapper,
                null, orderMapper, operLogMapper,
                new OperLogRecorderImpl(operLogMapper, userNameResolver), null, null,
                null, null, null,
                messageService, orderService, userNameResolver, null, null, null,
                new ObjectMapper(), null);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("已结算订单强制取消 → 3011（否则该单会从结算台账里凭空消失）")
    void forceCancelSettledOrderShouldBeRejected() {
        givenOrder(order(OrderStatus.COMPLETED, PaymentStatus.SETTLED));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.arbitrate(ORDER_ID, arbitrateDto("CANCELLED")));

        assertEquals(ResultCode.ORDER_SETTLED_CANNOT_CANCEL.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("未结算订单强制取消 → 成功（纠纷处理的正常手段不能被削弱）")
    void forceCancelUnsettledOrderShouldSucceed() {
        givenOrder(order(OrderStatus.IN_SERVICE, PaymentStatus.UNPAID));

        var result = adminService.arbitrate(ORDER_ID, arbitrateDto("CANCELLED"));

        assertEquals("CANCELLED", result.getStatus());
    }

    @Test
    @DisplayName("已结算订单强制完成 → 成功（已完成本身不会让台账消失，不该被误拦）")
    void forceCompleteSettledOrderShouldSucceed() {
        // 起点用 IN_SERVICE：COMPLETED → COMPLETED 会被「目标与当前相同」拦成 3002，
        // 那条与结算无关。此处要验的只有「已结算」这一个维度不拦强制完成。
        givenOrder(order(OrderStatus.IN_SERVICE, PaymentStatus.SETTLED));

        var result = adminService.arbitrate(ORDER_ID, arbitrateDto("COMPLETED"));

        assertEquals("COMPLETED", result.getStatus());
    }

    /**
     * 状态 × 结算状态的矩阵收口：防止后续再加一条检查时漏掉某个组合。
     *
     * <p>期望值来自规格而不是重算代码逻辑：</p>
     * <ul>
     *   <li>终态（{@code REVIEWED} / {@code CANCELLED}）→ 3002（先于结算检查判定）</li>
     *   <li>非终态 + 已结算 → 3011</li>
     *   <li>非终态 + 未结算 → 放行（纠纷处理的正常手段）</li>
     * </ul>
     */
    @ParameterizedTest(name = "status={0} payment={1} → {2}")
    @MethodSource("cancelMatrix")
    void forceCancelMatrix(String status, String paymentStatus, int expectedCode) {
        givenOrder(order(OrderStatus.valueOf(status), PaymentStatus.valueOf(paymentStatus)));

        if (expectedCode == 0) {
            assertEquals("CANCELLED", adminService.arbitrate(ORDER_ID, arbitrateDto("CANCELLED")).getStatus());
            return;
        }
        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.arbitrate(ORDER_ID, arbitrateDto("CANCELLED")));
        assertEquals(expectedCode, ex.getCode());
    }

    static Stream<Arguments> cancelMatrix() {
        return Stream.of(
                Arguments.of("PENDING", "UNPAID", 0),
                Arguments.of("PENDING", "SETTLED", ResultCode.ORDER_SETTLED_CANNOT_CANCEL.getCode()),
                Arguments.of("ACCEPTED", "UNPAID", 0),
                Arguments.of("ACCEPTED", "SETTLED", ResultCode.ORDER_SETTLED_CANNOT_CANCEL.getCode()),
                Arguments.of("IN_SERVICE", "UNPAID", 0),
                Arguments.of("IN_SERVICE", "SETTLED", ResultCode.ORDER_SETTLED_CANNOT_CANCEL.getCode()),
                Arguments.of("COMPLETED", "UNPAID", 0),
                Arguments.of("COMPLETED", "SETTLED", ResultCode.ORDER_SETTLED_CANNOT_CANCEL.getCode()),
                Arguments.of("REVIEWED", "UNPAID", ResultCode.ORDER_STATUS_ILLEGAL.getCode()),
                Arguments.of("REVIEWED", "SETTLED", ResultCode.ORDER_STATUS_ILLEGAL.getCode()),
                Arguments.of("CANCELLED", "UNPAID", ResultCode.ORDER_STATUS_ILLEGAL.getCode()),
                Arguments.of("CANCELLED", "SETTLED", ResultCode.ORDER_STATUS_ILLEGAL.getCode()));
    }

    private void givenOrder(CompanionOrder order) {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);
        lenient().when(orderMapper.updateById(any(CompanionOrder.class))).thenReturn(1);
        // P1-3：仲裁标记走一条独立的条件更新，写不进去会整体回滚
        lenient().when(orderMapper.update(any(), any())).thenReturn(1);
    }

    private CompanionOrder order(OrderStatus status, PaymentStatus paymentStatus) {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NO-2001");
        order.setFamilyId(FAMILY_ID);
        order.setCompanionId(COMPANION_ID);
        order.setStatus(status.name());
        order.setPaymentStatus(paymentStatus.name());
        return order;
    }

    private ArbitrateDTO arbitrateDto(String targetStatus) {
        ArbitrateDTO dto = new ArbitrateDTO();
        dto.setTargetStatus(targetStatus);
        dto.setResult("家属反馈线下已自行完成，核实后由平台强制处置");
        return dto;
    }

    private void loginAsAdmin() {
        LoginUser loginUser = new LoginUser(ADMIN_ID, "admin", RoleConstants.ADMIN, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }
}
