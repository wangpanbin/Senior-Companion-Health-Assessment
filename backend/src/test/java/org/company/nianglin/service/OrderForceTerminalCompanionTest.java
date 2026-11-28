package org.company.nianglin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.MatchingProperties;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ArbitrateDTO;
import org.company.nianglin.entity.AdminOperLog;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P0-7 · 无陪诊员的订单不可强制完成。
 *
 * <p><b>为什么这条必须落在强制终态入口</b>：一张没人接的待接单订单被管理员强制完成后，
 * 订单进入 {@code COMPLETED}（终态），而评价要求订单有陪诊员 ——
 * 于是这张单<b>永远停在「已完成」且永远评价不了</b>，评价率的分母从此与它永久不匹配。
 * 家属看到的却是「订单数据异常，请联系管理员」。</p>
 *
 * <p>本类锁定「没有陪诊员 → 只能取消，不能完成」这条固有约束。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P0-7 · 无陪诊员的订单不可强制完成")
class OrderForceTerminalCompanionTest {

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
    @DisplayName("PENDING 且无陪诊员 · 强制完成 → 3002（复用状态码，message 指向下一步动作）")
    void forceCompleteWithoutCompanionShouldBeRejected() {
        CompanionOrder order = order(OrderStatus.PENDING);
        order.setCompanionId(null);
        givenOrder(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> adminService.arbitrate(ORDER_ID, arbitrateDto("COMPLETED")));

        assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("尚无陪诊员"),
                "提示必须直接指向下一步动作（改用强制取消），而不是笼统的状态错误：" + ex.getMessage());
    }

    @Test
    @DisplayName("PENDING 且无陪诊员 · 强制取消 → 成功（拒绝完成不能误伤取消）")
    void forceCancelWithoutCompanionShouldSucceed() {
        CompanionOrder order = order(OrderStatus.PENDING);
        order.setCompanionId(null);
        givenOrder(order);

        var result = adminService.arbitrate(ORDER_ID, arbitrateDto("CANCELLED"));

        assertNotNull(result);
        assertEquals("CANCELLED", result.getStatus());
        assertNotNull(order.getCancelTime());
    }

    @Test
    @DisplayName("IN_SERVICE 且有陪诊员 · 强制完成 → 成功（保护现状不被新检查误伤）")
    void forceCompleteWithCompanionShouldSucceed() {
        CompanionOrder order = order(OrderStatus.IN_SERVICE);
        givenOrder(order);

        var result = adminService.arbitrate(ORDER_ID, arbitrateDto("COMPLETED"));

        assertNotNull(result);
        assertEquals("COMPLETED", result.getStatus());
    }

    @Test
    @DisplayName("被拒的强制完成不写 admin_oper_log（没发生的动作不该进审计日志）")
    void rejectedForceCompleteShouldNotWriteOperLog() {
        CompanionOrder order = order(OrderStatus.PENDING);
        order.setCompanionId(null);
        givenOrder(order);

        assertThrows(BusinessException.class,
                () -> adminService.arbitrate(ORDER_ID, arbitrateDto("COMPLETED")));

        verify(operLogMapper, never()).insert(any(AdminOperLog.class));
        verify(messageService, never()).send(any(), any(), any(), any());
    }

    private void givenOrder(CompanionOrder order) {
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);
        // lenient：被拒的两条用例走不到 updateById，strict 模式会把它判成多余桩
        lenient().when(orderMapper.updateById(any(CompanionOrder.class))).thenReturn(1);
        // P1-3：仲裁标记走一条独立的条件更新，写不进去会整体回滚
        lenient().when(orderMapper.update(any(), any())).thenReturn(1);
    }

    private CompanionOrder order(OrderStatus status) {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NO-2001");
        order.setFamilyId(FAMILY_ID);
        order.setCompanionId(COMPANION_ID);
        order.setStatus(status.name());
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
