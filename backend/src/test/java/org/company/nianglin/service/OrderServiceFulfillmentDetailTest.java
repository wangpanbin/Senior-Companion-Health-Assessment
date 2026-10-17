package org.company.nianglin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.FulfillmentRisk;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.OrderReadMapper;
import org.company.nianglin.mapper.OrderRejectLogMapper;
import org.company.nianglin.mapper.OrderStatusLogMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.OrderServiceImpl;
import org.company.nianglin.service.support.OrderTransitionService;
import org.company.nianglin.vo.OrderFulfillmentVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 履约详情的参与方鉴权单测（V3.1 · T4）。
 *
 * <p>本组刻意<b>不为鉴权新写一套判定</b>：端点直接复用 {@code OrderService#requireInvolved}。
 * 复用带来的可测性收益是 —— 这里不需要重复验证规则，只需要验证
 * 「履约详情确实走了参与方判定」，并把两类无关方各钉一条 3004。
 * 如果将来有人为了「省一次查询」把它改成自己判，这两条会立刻变红。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("履约详情鉴权：与订单详情同一套参与方判定")
class OrderServiceFulfillmentDetailTest {

    private static final Long ORDER_ID = 1001L;
    private static final Long FAMILY_ID = 300L;
    private static final Long COMPANION_ID = 401L;
    private static final Long ELDER_USER_ID = 501L;

    @Mock
    private CompanionOrderMapper orderMapper;
    @Mock
    private OrderStatusLogMapper statusLogMapper;
    @Mock
    private OrderRejectLogMapper rejectLogMapper;
    @Mock
    private ElderProfileMapper elderProfileMapper;
    @Mock
    private CompanionProfileMapper companionProfileMapper;
    @Mock
    private SysUserMapper sysUserMapper;
    @Mock
    private OrderReadMapper orderReadMapper;
    @Mock
    private ElderService elderService;
    @Mock
    private MessageService messageService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private OrderTransitionService orderTransitionService;
    @Mock
    private OrderFeeItemService orderFeeItemService;
    @Mock
    private FulfillmentService fulfillmentService;

    @InjectMocks
    private OrderServiceImpl orderService;

    private CompanionOrder order;

    @BeforeEach
    void setUp() {
        order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NL20261017000001");
        order.setFamilyId(FAMILY_ID);
        order.setElderId(ELDER_USER_ID);
        order.setCompanionId(COMPANION_ID);
        order.setStatus(OrderStatus.ACCEPTED.name());
        order.setFulfillmentRisk(FulfillmentRisk.NORMAL.name());
        order.setUrgeCount(0);
        order.setCreateTime(LocalDateTime.now().minusHours(1));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 放行                                                                */
    /* ================================================================== */

    @Test
    @DisplayName("下单家属可读")
    void ownerFamilyShouldBeAllowed() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);
        given(fulfillmentService.fulfillmentDetail(any()))
                .willReturn(OrderFulfillmentVO.of(snapshot(), List.of()));

        assertEquals(ORDER_ID, orderService.fulfillmentDetail(ORDER_ID).getOrderId());
        verify(fulfillmentService).fulfillmentDetail(order);
    }

    @Test
    @DisplayName("接单陪诊员可读")
    void acceptedCompanionShouldBeAllowed() {
        loginAs(COMPANION_ID, RoleConstants.COMPANION);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);
        given(fulfillmentService.fulfillmentDetail(any()))
                .willReturn(OrderFulfillmentVO.of(snapshot(), List.of()));

        assertEquals(ORDER_ID, orderService.fulfillmentDetail(ORDER_ID).getOrderId());
    }

    @Test
    @DisplayName("管理员可读（与订单详情一致）")
    void adminShouldBeAllowed() {
        loginAs(900L, RoleConstants.ADMIN);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);
        given(fulfillmentService.fulfillmentDetail(any()))
                .willReturn(OrderFulfillmentVO.of(snapshot(), List.of()));

        assertEquals(ORDER_ID, orderService.fulfillmentDetail(ORDER_ID).getOrderId());
    }

    @Test
    @DisplayName("就诊老人本人可读")
    void orderElderShouldBeAllowed() {
        loginAs(ELDER_USER_ID, RoleConstants.ELDER);
        ElderProfile elder = new ElderProfile();
        elder.setId(ELDER_USER_ID);
        elder.setUserId(ELDER_USER_ID);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of(elder));
        given(fulfillmentService.fulfillmentDetail(any()))
                .willReturn(OrderFulfillmentVO.of(snapshot(), List.of()));

        assertEquals(ORDER_ID, orderService.fulfillmentDetail(ORDER_ID).getOrderId());
    }

    /* ================================================================== */
    /* 拒绝                                                                */
    /* ================================================================== */

    @Test
    @DisplayName("无关家属返回 3004，且不触碰履约服务")
    void unrelatedFamilyShouldBeRejected() {
        loginAs(999L, RoleConstants.FAMILY);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.fulfillmentDetail(ORDER_ID));

        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
        verify(fulfillmentService, never()).fulfillmentDetail(any());
    }

    @Test
    @DisplayName("无关陪诊员返回 3004，且不触碰履约服务")
    void unrelatedCompanionShouldBeRejected() {
        loginAs(888L, RoleConstants.COMPANION);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.fulfillmentDetail(ORDER_ID));

        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
        verify(fulfillmentService, never()).fulfillmentDetail(any());
    }

    @Test
    @DisplayName("订单不存在返回 3001")
    void missingOrderShouldBeRejected() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(orderMapper.selectById(ORDER_ID)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.fulfillmentDetail(ORDER_ID));

        assertEquals(ResultCode.ORDER_NOT_FOUND.getCode(), ex.getCode());
    }

    /* ================================================================== */
    /* 委托契约                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("鉴权后把同一个订单实例交给履约服务，不重新查一次库")
    void shouldPassTheSameOrderInstanceThrough() {
        loginAs(FAMILY_ID, RoleConstants.FAMILY);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);
        given(fulfillmentService.fulfillmentDetail(any()))
                .willReturn(OrderFulfillmentVO.of(snapshot(), List.of()));

        orderService.fulfillmentDetail(ORDER_ID);

        // 若实现改成自己再查一次库，这里会因为 selectById 被调用两次而失败
        verify(orderMapper).selectById(ORDER_ID);
        verify(fulfillmentService).fulfillmentDetail(order);
    }

    /* ================================================================== */
    /* 测试夹具                                                            */
    /* ================================================================== */

    private void loginAs(Long userId, String role) {
        LoginUser loginUser = new LoginUser(userId, "u" + userId, role, 0,
                "jti-test", System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    private OrderFulfillmentVO.CompanionOrderSnapshot snapshot() {
        return new OrderFulfillmentVO.CompanionOrderSnapshot(ORDER_ID, "NL20261017000001",
                OrderStatus.ACCEPTED.name(), "已接单", "NORMAL", "正常等待", 0, 0, false, null);
    }
}
