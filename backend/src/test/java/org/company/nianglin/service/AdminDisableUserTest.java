package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.AccountStatus;
import org.company.nianglin.constant.ActiveOrderAction;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.UserDisableDTO;
import org.company.nianglin.entity.AdminOperLog;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.OrderStatusLog;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.AdminOperLogMapper;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.OrderFulfillmentEventMapper;
import org.company.nianglin.mapper.OrderStatusLogMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.TokenStore;
import org.company.nianglin.service.impl.AdminServiceImpl;
import org.company.nianglin.service.support.OperLogRecorderImpl;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.support.MybatisLambdaCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P0-4 · 封禁陪诊员须处理在手订单。
 *
 * <p>管理员封禁一个正在服务中的陪诊员之后，他名下的「已接单 / 服务中」订单
 * 会变成无主订单：他打不了卡、完不成订单，家属取消不了（取消只允许待接单），
 * 履约风险中心也看不到（只扫待接单），而后台没有任何告警 ——
 * 订单永远停在「服务中」，只能靠人发现。</p>
 *
 * <p>本类锁定「封禁前必须先处置在手订单」这条约束。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P0-4 · 封禁陪诊员须处理在手订单")
class AdminDisableUserTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long COMPANION_USER_ID = 301L;
    private static final Long ORDER_ID = 2001L;

    @Mock
    private CompanionOrderMapper orderMapper;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private AdminOperLogMapper operLogMapper;

    @Mock
    private OrderFulfillmentEventMapper fulfillmentEventMapper;

    @Mock
    private OrderStatusLogMapper orderStatusLogMapper;

    @Mock
    private MessageService messageService;

    @Mock
    private OrderService orderService;

    @Mock
    private UserNameResolver userNameResolver;

    @Mock
    private TokenStore tokenStore;

    private AdminServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        loginAsAdmin();

        service = new AdminServiceImpl(null, companionProfileMapper, sysUserMapper,
                null, orderMapper, operLogMapper,
                new OperLogRecorderImpl(operLogMapper, userNameResolver), null, null,
                null, null, fulfillmentEventMapper,
                messageService, orderService, userNameResolver, null, null,
                tokenStore, new ObjectMapper(), orderStatusLogMapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("封禁无在手订单的陪诊员 → 成功（保护现状不被新规则误伤）")
    void disableWithoutActiveOrderShouldSucceed() {
        givenUser();
        given(orderMapper.selectList(any())).willReturn(List.of());

        var vo = service.disableUser(COMPANION_USER_ID, disableDto(null));

        assertEquals(AccountStatus.DISABLED, vo.getStatus());
        verify(sysUserMapper).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("封禁有 ACCEPTED 单的陪诊员且未指定处置 → 2012，data 带在途单张数")
    void disableWithAcceptedOrderShouldRequireDisposition() {
        givenUser();
        given(orderMapper.selectList(any())).willReturn(List.of(activeOrder(OrderStatus.ACCEPTED)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.disableUser(COMPANION_USER_ID, disableDto(null)));

        assertEquals(ResultCode.COMPANION_HAS_ACTIVE_ORDER.getCode(), ex.getCode());
        Map<?, ?> data = (Map<?, ?>) ex.getData();
        assertNotNull(data, "data 必须带上在途单张数，前端据此弹处置选择框");
        assertEquals(1, ((Number) data.get("activeOrderCount")).intValue());
        // 拒绝发生在任何落库动作之前
        verify(sysUserMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("封禁有 IN_SERVICE 单的陪诊员且未指定处置 → 2012")
    void disableWithInServiceOrderShouldRequireDisposition() {
        givenUser();
        given(orderMapper.selectList(any())).willReturn(List.of(activeOrder(OrderStatus.IN_SERVICE)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.disableUser(COMPANION_USER_ID, disableDto(null)));

        assertEquals(ResultCode.COMPANION_HAS_ACTIVE_ORDER.getCode(), ex.getCode());
        verify(sysUserMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("选择 RELEASE → 订单改回 PENDING 并清空接单人（与 P0-1 改派同一套语义）")
    void releaseShouldReturnOrderToHall() {
        givenUser();
        given(orderMapper.selectList(any())).willReturn(List.of(activeOrder(OrderStatus.IN_SERVICE)));
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(1);

        service.disableUser(COMPANION_USER_ID, disableDto(ActiveOrderAction.RELEASE));

        ArgumentCaptor<LambdaUpdateWrapper<CompanionOrder>> captor = wrapperCaptor();
        verify(orderMapper).update(any(), captor.capture());
        String sqlSet = captor.getValue().getSqlSet();
        assertTrue(sqlSet.contains("companion_id = NULL"),
                "放回大厅必须清空接单人：" + sqlSet);
        assertTrue(sqlSet.contains("version = version + 1"),
                "放回大厅必须递增 version：" + sqlSet);

        // 状态日志：操作人是系统，不是某个人
        ArgumentCaptor<OrderStatusLog> logCaptor = ArgumentCaptor.forClass(OrderStatusLog.class);
        verify(orderStatusLogMapper).insert(logCaptor.capture());
        assertEquals(RoleConstants.SYSTEM, logCaptor.getValue().getOperatorRole());
        assertEquals(OrderStatus.PENDING.name(), logCaptor.getValue().getToStatus());
    }

    @Test
    @DisplayName("选择 CANCEL → 走 forceTerminal 而不是自己写状态")
    void cancelShouldDelegateToForceTerminal() {
        givenUser();
        given(orderMapper.selectList(any())).willReturn(List.of(activeOrder(OrderStatus.ACCEPTED)));

        service.disableUser(COMPANION_USER_ID, disableDto(ActiveOrderAction.CANCEL));

        verify(orderService).forceTerminal(eq(ORDER_ID), eq(OrderStatus.CANCELLED), any());
        // 不自己写状态
        verify(orderMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("两种处置都要通知家属（家属不能被蒙在鼓里）")
    void bothDispositionsShouldNotifyFamily() {
        givenUser();
        given(orderMapper.selectList(any())).willReturn(List.of(activeOrder(OrderStatus.ACCEPTED)));
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(1);

        service.disableUser(COMPANION_USER_ID, disableDto(ActiveOrderAction.RELEASE));
        verify(messageService).send(eq(100L), eq(MessageType.SYSTEM_NOTICE), eq(ORDER_ID), any());

        given(orderMapper.selectList(any())).willReturn(List.of(activeOrder(OrderStatus.ACCEPTED)));
        service.disableUser(COMPANION_USER_ID, disableDto(ActiveOrderAction.CANCEL));
        verify(messageService).send(eq(100L), eq(MessageType.ORDER_CANCELLED), eq(ORDER_ID), any());
    }

    @Test
    @DisplayName("处置失败 → 封禁整体不落库（不留半封禁状态）")
    void dispositionFailureShouldAbortDisable() {
        givenUser();
        given(orderMapper.selectList(any())).willReturn(List.of(activeOrder(OrderStatus.ACCEPTED)));
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(1);

        // 处置过程中抛错（这里用「通知家属」失败模拟），封禁必须整体不落库
        org.mockito.Mockito.doThrow(new IllegalStateException("通知发送失败"))
                .when(messageService).send(any(), any(), any(), any());

        assertThrows(IllegalStateException.class,
                () -> service.disableUser(COMPANION_USER_ID, disableDto(ActiveOrderAction.RELEASE)));

        verify(sysUserMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("封禁管理员账号仍应返回 8002（既有规则优先于新规则）")
    void disableAdminShouldStillReturn8002() {
        given(sysUserMapper.selectById(ADMIN_ID)).willReturn(adminUser());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.disableUser(ADMIN_ID, disableDto(null)));

        assertEquals(ResultCode.CANNOT_DISABLE_ADMIN.getCode(), ex.getCode());
    }

    private void givenUser() {
        given(sysUserMapper.selectById(COMPANION_USER_ID)).willReturn(companionUser());
    }

    private org.company.nianglin.entity.SysUser companionUser() {
        var user = new org.company.nianglin.entity.SysUser();
        user.setId(COMPANION_USER_ID);
        user.setUsername("companion301");
        user.setRole(RoleConstants.COMPANION);
        user.setStatus(AccountStatus.NORMAL);
        return user;
    }

    private org.company.nianglin.entity.SysUser adminUser() {
        var user = new org.company.nianglin.entity.SysUser();
        user.setId(ADMIN_ID);
        user.setUsername("admin");
        user.setRole(RoleConstants.ADMIN);
        user.setStatus(AccountStatus.NORMAL);
        return user;
    }

    private CompanionOrder activeOrder(OrderStatus status) {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NL20261128000001");
        order.setFamilyId(100L);
        order.setCompanionId(COMPANION_USER_ID);
        order.setStatus(status.name());
        return order;
    }

    private UserDisableDTO disableDto(ActiveOrderAction action) {
        UserDisableDTO dto = new UserDisableDTO();
        dto.setReason("多次爽约且未提前告知家属，已严重影响服务");
        dto.setActiveOrderAction(action);
        return dto;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<LambdaUpdateWrapper<CompanionOrder>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    private void loginAsAdmin() {
        LoginUser loginUser = new LoginUser(ADMIN_ID, "admin", RoleConstants.ADMIN, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }
}
