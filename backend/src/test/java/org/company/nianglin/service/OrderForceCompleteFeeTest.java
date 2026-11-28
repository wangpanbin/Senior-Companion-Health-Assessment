package org.company.nianglin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.config.MatchingProperties;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.PaymentStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ArbitrateDTO;
import org.company.nianglin.entity.CompanionOrder;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P0-6 · 强制完成须走费用汇总。
 *
 * <p>陪诊员正常完成服务时，系统把「服务费」写成一条费用明细并重算 {@code actual_fee}；
 * 而管理员<b>强制完成</b>时什么都不算 —— {@code actual_fee} 保持空。而结算金额取的是
 * {@code COALESCE(actual_fee, fee)}，于是陪诊员先垫的挂号费、药费不进结算，
 * 系统一声不响，陪诊员少收钱。</p>
 *
 * <p>本类锁定「强制完成与正常完成走同一套费用汇总」。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P0-6 · 强制完成须走费用汇总")
class OrderForceCompleteFeeTest {

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
    @DisplayName("强制完成 → 调用费用汇总且申报费传 null（管理员强制时陪诊员可能没申报服务费）")
    void forceCompleteShouldApplyFeeSummary() {
        givenOrder(order(OrderStatus.IN_SERVICE));

        adminService.arbitrate(ORDER_ID, arbitrateDto("COMPLETED"));

        verify(orderFeeItemService).applyOnComplete(ORDER_ID, null);
    }

    @Test
    @DisplayName("强制取消 → 不调用费用汇总（防止误伤）")
    void forceCancelShouldNotApplyFeeSummary() {
        givenOrder(order(OrderStatus.IN_SERVICE));

        adminService.arbitrate(ORDER_ID, arbitrateDto("CANCELLED"));

        verify(orderFeeItemService, never()).applyOnComplete(any(), any());
    }

    @Test
    @DisplayName("费用汇总抛异常 → 异常向上抛出（同事务回滚由 @Transactional 保证）")
    void feeSummaryFailureShouldPropagate() {
        givenOrder(order(OrderStatus.IN_SERVICE));
        doThrow(new IllegalStateException("费用明细写入失败"))
                .when(orderFeeItemService).applyOnComplete(ORDER_ID, null);

        assertThrows(IllegalStateException.class,
                () -> adminService.arbitrate(ORDER_ID, arbitrateDto("COMPLETED")));
    }

    private void givenOrder(CompanionOrder order) {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);
        lenient().when(orderMapper.updateById(any(CompanionOrder.class))).thenReturn(1);
        // P1-3：仲裁标记走一条独立的条件更新，写不进去（影响行 0）会整体回滚并抛错
        lenient().when(orderMapper.update(any(), any())).thenReturn(1);
    }

    private CompanionOrder order(OrderStatus status) {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NO-2001");
        order.setFamilyId(FAMILY_ID);
        order.setCompanionId(COMPANION_ID);
        order.setStatus(status.name());
        order.setPaymentStatus(PaymentStatus.UNPAID.name());
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
