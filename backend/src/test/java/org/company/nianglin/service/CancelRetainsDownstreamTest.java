package org.company.nianglin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.config.MatchingProperties;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.OrderCancelDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.OrderCheckin;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.CompanionTrackMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.OrderCheckinMapper;
import org.company.nianglin.mapper.OrderReadMapper;
import org.company.nianglin.mapper.OrderRejectLogMapper;
import org.company.nianglin.mapper.OrderStatusLogMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.ExecutionServiceImpl;
import org.company.nianglin.service.impl.OrderServiceImpl;
import org.company.nianglin.service.support.OrderTransitionService;
import org.company.nianglin.service.support.OrderTransitionServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.CheckinVO;
import org.company.nianglin.websocket.OrderProgressHub;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 订单取消后下游数据的保留（closure-2026-11 · P2-5 · ADR-0023）。
 *
 * <p>本类是<b>保护性测试</b>：它锁住的不是「要新增什么功能」，而是「不要顺手加级联删除」。
 * 取消之后打卡、轨迹、聊天、附件、站内信一律保留 —— 理由见 ADR-0023：
 * 取消常常是纠纷的结果或起点，删掉过程数据等于让管理员在没有证据的情况下仲裁；
 * 陪诊员已经到院打卡的记录是他的劳动凭证。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("取消保留下游：打卡记录仍在，且取消不删任何下游数据")
class CancelRetainsDownstreamTest {

    private static final Long ORDER_ID = 1033L;
    private static final Long FAMILY_ID = 103L;

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
    /** 打卡查询用的订单服务（鉴权走它）；取消路径另用真实实现 */
    @Mock
    private OrderService orderServiceMock;
    @Mock
    private MessageService messageService;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private OrderFeeItemService orderFeeItemService;
    @Mock
    private FulfillmentService fulfillmentService;
    @Mock
    private MatchingService matchingService;
    @Mock
    private OrderCheckinMapper orderCheckinMapper;
    @Mock
    private CompanionTrackMapper trackMapper;
    @Mock
    private FileStorageService fileStorageService;
    @Mock
    private OrderProgressHub progressHub;

    private OrderServiceImpl orderService;
    private ExecutionServiceImpl executionService;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        OrderTransitionService transitionService = new OrderTransitionServiceImpl(
                orderMapper, statusLogMapper, companionProfileMapper, sysUserMapper);
        orderService = new OrderServiceImpl(orderMapper, statusLogMapper, rejectLogMapper,
                elderProfileMapper, companionProfileMapper, sysUserMapper, orderReadMapper,
                elderService, messageService, redisTemplate, new ObjectMapper(),
                transitionService, orderFeeItemService, fulfillmentService,
                new org.company.nianglin.config.FulfillmentProperties(),
                matchingService, new MatchingProperties(), orderCheckinMapper);
        executionService = new ExecutionServiceImpl(orderMapper, orderCheckinMapper, trackMapper,
                sysUserMapper, companionProfileMapper, orderServiceMock, fileStorageService,
                messageService, progressHub, new ObjectMapper(), redisTemplate,
                new org.company.nianglin.config.TrackingProperties());
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */

    @Test
    @DisplayName("取消后打卡记录仍可查询（ADR-0023：取消不清理下游）")
    void checkinsShouldRemainQueryableAfterCancellation() {
        CompanionOrder cancelled = order(OrderStatus.CANCELLED);
        given(orderServiceMock.requireInvolved(ORDER_ID)).willReturn(cancelled);
        given(orderCheckinMapper.selectList(any())).willReturn(List.of(
                checkin(1L, "ARRIVE"), checkin(2L, "FINISH")));

        List<CheckinVO> voList = executionService.checkins(ORDER_ID);

        assertEquals(2, voList.size(),
                "取消之后打卡记录必须仍可查 —— 它既是纠纷证据也是陪诊员的劳动凭证");
    }

    @Test
    @DisplayName("取消不删除任何打卡记录（防止后人顺手加级联删除）")
    void cancelShouldNotDeleteDownstreamData() {
        CompanionOrder order = order(OrderStatus.PENDING);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);
        given(orderMapper.updateById(any(CompanionOrder.class))).willReturn(1);

        OrderCancelDTO dto = new OrderCancelDTO();
        dto.setReason("临时有事，改约下次");
        orderService.cancel(ORDER_ID, dto);

        assertEquals(OrderStatus.CANCELLED.name(), order.getStatus());
        verify(orderCheckinMapper, never()).delete(any());
        verify(orderCheckinMapper, never()).deleteById(any(Long.class));
        verify(orderCheckinMapper, never()).deleteBatchIds(any());
    }

    /* ================================================================== */

    private CompanionOrder order(OrderStatus status) {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NL20260822000033");
        order.setFamilyId(FAMILY_ID);
        order.setElderId(403L);
        order.setCompanionId(309L);
        order.setStatus(status.name());
        return order;
    }

    private OrderCheckin checkin(Long id, String node) {
        OrderCheckin checkin = new OrderCheckin();
        checkin.setId(id);
        checkin.setOrderId(ORDER_ID);
        checkin.setCompanionId(309L);
        checkin.setNode(node);
        checkin.setCheckinTime(LocalDateTime.now().minusHours(3));
        return checkin;
    }

    private void loginAs(String role, Long userId) {
        LoginUser loginUser = new LoginUser(userId, "tester", role, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }
}
