package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.FulfillmentProperties;
import org.company.nianglin.config.MatchingProperties;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.PaymentStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.OrderCompleteDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.OrderCheckin;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.OrderCheckinMapper;
import org.company.nianglin.mapper.OrderStatusLogMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.OrderServiceImpl;
import org.company.nianglin.service.support.OrderTransitionService;
import org.company.nianglin.service.support.OrderTransitionServiceImpl;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * P0-3 · 完成服务须有打卡留痕。
 *
 * <p>陪诊员接单之后可以直接「完成服务」把订单结束，一个打卡都不做 ——
 * 订单变成「已完成」，家属能去评价，而打卡表、轨迹表、服务照片全是空的。
 * 更要紧的是订单一旦完成就<b>再也补不了打卡</b>（打卡要求订单处于已接单或服务中），
 * 事后要证明「确实到院了、确实取药了」，系统里没有任何证据。</p>
 *
 * <p>本类锁定：完成前必须有 {@code ARRIVE} / {@code FINISH} 打卡；
 * 出发打卡不算；开关可关；校验失败时不得写入任何状态变更。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("P0-3 · 完成服务须有打卡留痕")
class OrderCompleteCheckinTest {

    private static final Long COMPANION_ID = 301L;
    private static final Long FAMILY_ID = 101L;
    private static final Long ORDER_ID = 2001L;

    @Mock
    private CompanionOrderMapper orderMapper;

    @Mock
    private OrderStatusLogMapper statusLogMapper;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private MessageService messageService;

    @Mock
    private OrderFeeItemService orderFeeItemService;

    @Mock
    private OrderCheckinMapper orderCheckinMapper;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        loginAsCompanion();

        OrderTransitionService transitionService = new OrderTransitionServiceImpl(
                orderMapper, statusLogMapper, companionProfileMapper, sysUserMapper);
        orderService = new OrderServiceImpl(orderMapper, statusLogMapper, null,
                null, companionProfileMapper, sysUserMapper, null,
                null, messageService, null, new ObjectMapper(),
                transitionService, orderFeeItemService, null, new FulfillmentProperties(),
                null, new MatchingProperties(), orderCheckinMapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("没有任何打卡时完成 → 3012，且不写入任何状态变更")
    void completeWithoutCheckinShouldBeRejected() {
        givenCompanionAndOrder();
        given(orderCheckinMapper.selectCount(any())).willReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.complete(ORDER_ID, new OrderCompleteDTO()));

        assertEquals(ResultCode.ORDER_CHECKIN_REQUIRED.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("打卡"), "提示要指向下一步动作：" + ex.getMessage());
        verify(orderMapper, never()).updateById(any(CompanionOrder.class));
    }

    @Test
    @DisplayName("只有「出发」打卡时完成仍 → 3012（凭证链不能断在关键节点，不是「有打卡就过」）")
    void completeWithOnlyDepartCheckinShouldBeRejected() {
        givenCompanionAndOrder();
        given(orderCheckinMapper.selectCount(any())).willReturn(0L);

        assertThrows(BusinessException.class,
                () -> orderService.complete(ORDER_ID, new OrderCompleteDTO()));

        // 断言查询口径本身：只认「到院」与「完成」两个节点，不得把「出发」算进来
        ArgumentCaptor<Wrapper<OrderCheckin>> captor = wrapperCaptor();
        verify(orderCheckinMapper).selectCount(captor.capture());
        AbstractWrapper<?, ?, ?> wrapper = (AbstractWrapper<?, ?, ?>) captor.getValue();
        // 先渲染一次 SQL —— MyBatis-Plus 的 IN 条件值是在渲染时才写进参数表的
        wrapper.getSqlSegment();
        String nodes = String.valueOf(wrapper.getParamNameValuePairs().values());
        assertTrue(nodes.contains("ARRIVE"), "必须查「到院」打卡：" + nodes);
        assertTrue(nodes.contains("FINISH"), "必须查「完成」打卡：" + nodes);
        assertTrue(!nodes.contains("DEPART"), "「出发」打卡不能证明到院：" + nodes);
    }

    @Test
    @DisplayName("有「到院」打卡时完成 → 成功")
    void completeWithArriveCheckinShouldSucceed() {
        givenCompanionAndOrder();
        given(orderCheckinMapper.selectCount(any())).willReturn(1L);
        given(orderMapper.updateById(any(CompanionOrder.class))).willReturn(1);

        var result = orderService.complete(ORDER_ID, new OrderCompleteDTO());

        assertEquals(OrderStatus.COMPLETED.name(), result.getStatus());
    }

    @Test
    @DisplayName("有「完成」打卡时完成 → 成功")
    void completeWithFinishCheckinShouldSucceed() {
        givenCompanionAndOrder();
        given(orderCheckinMapper.selectCount(any())).willReturn(1L);
        given(orderMapper.updateById(any(CompanionOrder.class))).willReturn(1);

        var result = orderService.complete(ORDER_ID, new OrderCompleteDTO());

        assertEquals(OrderStatus.COMPLETED.name(), result.getStatus());
    }

    @Test
    @DisplayName("开关关闭时无打卡也能完成（历史数据回填与应急降级）")
    void completeShouldPassWhenCheckinCheckDisabled() {
        ReflectionTestUtils.setField(orderService, "requireCheckinOnComplete", Boolean.FALSE);
        givenCompanionAndOrder();
        given(orderMapper.updateById(any(CompanionOrder.class))).willReturn(1);

        var result = orderService.complete(ORDER_ID, new OrderCompleteDTO());

        assertEquals(OrderStatus.COMPLETED.name(), result.getStatus());
        verify(orderCheckinMapper, never()).selectCount(any());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ArgumentCaptor<Wrapper<org.company.nianglin.entity.OrderCheckin>> wrapperCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Wrapper.class);
    }

    private void givenCompanionAndOrder() {
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(companionProfile()));
        given(orderMapper.selectById(ORDER_ID)).willReturn(inServiceOrder());
    }

    private CompanionOrder inServiceOrder() {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NL20260915000001");
        order.setFamilyId(FAMILY_ID);
        order.setCompanionId(COMPANION_ID);
        order.setStatus(OrderStatus.IN_SERVICE.name());
        order.setFee(new BigDecimal("128.00"));
        order.setPaymentStatus(PaymentStatus.UNPAID.name());
        order.setVersion(0);
        return order;
    }

    private CompanionProfile companionProfile() {
        CompanionProfile profile = new CompanionProfile();
        profile.setId(601L);
        profile.setUserId(COMPANION_ID);
        profile.setRealName("李建军");
        profile.setAuditStatus(AuditStatus.APPROVED.name());
        return profile;
    }

    private void loginAsCompanion() {
        LoginUser loginUser = new LoginUser(COMPANION_ID, "companion", RoleConstants.COMPANION, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }
}
