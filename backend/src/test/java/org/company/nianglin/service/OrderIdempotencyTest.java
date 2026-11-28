package org.company.nianglin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.FulfillmentProperties;
import org.company.nianglin.config.MatchingProperties;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.OrderCreateDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.OrderCheckinMapper;
import org.company.nianglin.mapper.OrderReadMapper;
import org.company.nianglin.mapper.OrderRejectLogMapper;
import org.company.nianglin.mapper.OrderStatusLogMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.OrderServiceImpl;
import org.company.nianglin.service.support.OrderTransitionService;
import org.company.nianglin.service.support.OrderTransitionServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.OrderCreateResultVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 下单幂等键（closure-2026-11 · P2-4）。
 *
 * <p>家属在弱网下点「提交订单」，前端超时重试，会产生<b>两笔独立订单</b> ——
 * 订单号每次都是新生成的，两笔单都是合法的，系统唯一的约束挡不住。
 * 幂等键让同一个键的重复提交返回<b>同一笔订单</b>。</p>
 *
 * <p>三条不能破的规则：</p>
 * <ol>
 *   <li><b>不传键 = 老行为</b>：老客户端不受影响。</li>
 *   <li><b>Redis 挂了要放行</b>：缓存故障不该阻断下单 —— 重复下单用户自己能取消一笔，
 *       下不了单则一步也走不下去。</li>
 *   <li><b>不同键不能合并</b>：两笔不相干的订单被并成一笔，比重复下单严重得多。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("下单幂等：同键同单 / 不同键两单 / Redis 故障放行")
class OrderIdempotencyTest {

    private static final Long FAMILY_ID = 101L;
    private static final Long ELDER_ID = 401L;
    private static final Long ORDER_ID = 2001L;
    private static final String KEY = "order-20261128-0001";

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
    private ValueOperations<String, String> valueOps;
    @Mock
    private OrderFeeItemService orderFeeItemService;
    @Mock
    private FulfillmentService fulfillmentService;
    @Mock
    private MatchingService matchingService;
    @Mock
    private OrderCheckinMapper orderCheckinMapper;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        OrderTransitionService transitionService = new OrderTransitionServiceImpl(
                orderMapper, statusLogMapper, companionProfileMapper, sysUserMapper);
        orderService = new OrderServiceImpl(orderMapper, statusLogMapper, rejectLogMapper,
                elderProfileMapper, companionProfileMapper, sysUserMapper, orderReadMapper,
                elderService, messageService, redisTemplate, new ObjectMapper(),
                transitionService, orderFeeItemService, fulfillmentService, new FulfillmentProperties(),
                matchingService, new MatchingProperties(), orderCheckinMapper);

        given(redisTemplate.opsForValue()).willReturn(valueOps);
        // 订单号序列（发号走 Redis INCR）；lenient：409 那条用例根本不会发号
        lenient().when(valueOps.increment(anyString())).thenReturn(1L);
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */

    @Test
    @DisplayName("幂等 · 同一幂等键二次下单：返回同一笔订单，不产生第二笔")
    void sameKeyShouldReturnSameOrder() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elderProfile());
        given(orderMapper.insert(any(CompanionOrder.class))).willAnswer(inv -> {
            CompanionOrder order = inv.getArgument(0);
            order.setId(ORDER_ID);
            return 1;
        });
        // 第一次抢到锁，第二次没抢到
        given(valueOps.setIfAbsent(anyString(), anyString(), any())).willReturn(true, false);
        // 第二次读结果缓存 → 命中第一次那笔
        given(valueOps.get(anyString())).willReturn(String.valueOf(ORDER_ID));
        given(orderMapper.selectById(ORDER_ID)).willReturn(order());

        OrderCreateResultVO first = orderService.create(createDto(), KEY);
        OrderCreateResultVO second = orderService.create(createDto(), KEY);

        assertEquals(ORDER_ID, first.getOrderId());
        assertEquals(first.getOrderId(), second.getOrderId(),
                "同一幂等键必须返回同一笔订单，而不是又建一笔");
        assertEquals(first.getOrderNo(), second.getOrderNo());
        verify(orderMapper, times(1)).insert(any(CompanionOrder.class));
    }

    @Test
    @DisplayName("幂等 · 不同幂等键：两笔订单（不能误合并）")
    void differentKeysShouldCreateTwoOrders() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elderProfile());
        given(orderMapper.insert(any(CompanionOrder.class))).willAnswer(inv -> {
            CompanionOrder order = inv.getArgument(0);
            order.setId(ORDER_ID);
            return 1;
        });
        given(valueOps.setIfAbsent(anyString(), anyString(), any())).willReturn(true);

        orderService.create(createDto(), "key-A");
        orderService.create(createDto(), "key-B");

        verify(orderMapper, times(2)).insert(any(CompanionOrder.class));
    }

    @Test
    @DisplayName("幂等 · 不传幂等键：行为与从前完全一致（不碰幂等键）")
    void noKeyShouldBehaveAsBefore() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elderProfile());
        given(orderMapper.insert(any(CompanionOrder.class))).willAnswer(inv -> {
            CompanionOrder order = inv.getArgument(0);
            order.setId(ORDER_ID);
            return 1;
        });

        OrderCreateResultVO result = orderService.create(createDto(), null);

        assertEquals(ORDER_ID, result.getOrderId());
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("幂等 · Redis 不可用：放行下单（缓存故障不该阻断主流程）")
    void redisFailureShouldNotBlockOrder() {
        given(elderService.requireAccessible(ELDER_ID)).willReturn(elderProfile());
        given(orderMapper.insert(any(CompanionOrder.class))).willAnswer(inv -> {
            CompanionOrder order = inv.getArgument(0);
            order.setId(ORDER_ID);
            return 1;
        });
        given(valueOps.setIfAbsent(anyString(), anyString(), any()))
                .willThrow(new IllegalStateException("redis down"));

        OrderCreateResultVO result = orderService.create(createDto(), KEY);

        assertEquals(ORDER_ID, result.getOrderId(), "Redis 挂了也要能下单");
        verify(orderMapper).insert(any(CompanionOrder.class));
    }

    @Test
    @DisplayName("幂等 · 抢不到锁且没有结果缓存 → 409（第一次还在处理中）")
    void inFlightShouldReturnConflict() {
        // 不 stub 老人档案：409 应该在进入下单逻辑之前就返回
        given(valueOps.setIfAbsent(anyString(), anyString(), any())).willReturn(false);
        given(valueOps.get(anyString())).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.create(createDto(), KEY));

        assertEquals(ResultCode.CONFLICT.getCode(), ex.getCode());
        verify(orderMapper, never()).insert(any(CompanionOrder.class));
    }

    /* ================================================================== */

    private ElderProfile elderProfile() {
        ElderProfile elder = new ElderProfile();
        elder.setId(ELDER_ID);
        elder.setName("张*明");
        return elder;
    }

    private CompanionOrder order() {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NL20261128000001");
        order.setFamilyId(FAMILY_ID);
        order.setElderId(ELDER_ID);
        order.setStatus("PENDING");
        return order;
    }

    private OrderCreateDTO createDto() {
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setElderId(ELDER_ID);
        dto.setHospital("海口市人民医院");
        dto.setDepartment("心内科");
        dto.setVisitTime(LocalDateTime.now().plusDays(1));
        dto.setAddress("海口市美兰区蓝天路 1 号");
        return dto;
    }

    private void loginAs(String role, Long userId) {
        LoginUser loginUser = new LoginUser(userId, "tester", role, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }
}
