package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.FulfillmentProperties;
import org.company.nianglin.config.MatchingProperties;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.constant.WorkStatus;
import org.company.nianglin.dto.OrderHallQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.dto.OrderCreateDTO;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.MatchingMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.OrderCheckinMapper;
import org.company.nianglin.mapper.OrderReadMapper;
import org.company.nianglin.mapper.OrderRejectLogMapper;
import org.company.nianglin.mapper.OrderStatusLogMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.MatchingServiceImpl;
import org.company.nianglin.service.impl.OrderServiceImpl;
import org.company.nianglin.service.support.OrderTransitionService;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.OrderRecommendVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 接单开关「状态生效」单测（收口 B · P0-2）。
 *
 * <p>与 {@link CompanionWorkStatusTest} 的分工：那类测「切换状态」这件事本身，
 * 本类测「切完之后状态在别处真的生效」——大厅、抢单、下单广播三处。</p>
 *
 * @author 银龄伴诊团队
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("接单开关：状态在三处真正生效（P0-2）")
class CompanionAcceptingGateTest {

    private static final Long COMPANION_ID = 301L;

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
    private OrderFeeItemService orderFeeItemService;
    @Mock
    private FulfillmentService fulfillmentService;
    @Mock
    private MatchingService matchingService;
    @Mock
    private OrderTransitionService orderTransitionService;
    @Mock
    private MatchingMapper matchingMapper;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private OrderCheckinMapper orderCheckinMapper;

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        // 大厅/接单链路不碰状态流转入口（ADR-0010），这里传 null
        orderService = new OrderServiceImpl(orderMapper, statusLogMapper, rejectLogMapper,
                elderProfileMapper, companionProfileMapper, sysUserMapper, orderReadMapper,
                elderService, messageService, redisTemplate, new ObjectMapper(),
                orderTransitionService, orderFeeItemService, fulfillmentService, new FulfillmentProperties(),
                matchingService, new MatchingProperties(), orderCheckinMapper);
        loginAsCompanion();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("休息中陪诊员进大厅应返回 2013，而不是拿空列表骗他「今天没单」")
    void restCompanionShouldBeRejectedFromHall() {
        stubProfile(WorkStatus.REST);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.hall(new OrderHallQuery()));

        assertEquals(ResultCode.COMPANION_NOT_ACCEPTING.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("离线陪诊员进大厅同样返回 2013（证明不只判 REST）")
    void offlineCompanionShouldBeRejectedFromHall() {
        stubProfile(WorkStatus.OFFLINE);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.hall(new OrderHallQuery()));

        assertEquals(ResultCode.COMPANION_NOT_ACCEPTING.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("不可识别的接单状态按 fail-closed 处理，返回 2013")
    void unknownWorkStatusShouldBeRejectedFromHall() {
        stubProfileRaw("BUSY");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> orderService.hall(new OrderHallQuery()));

        assertEquals(ResultCode.COMPANION_NOT_ACCEPTING.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("可接单陪诊员进大厅仍正常返回（保护现状）")
    void availableCompanionShouldSeeHall() {
        stubProfile(WorkStatus.AVAILABLE);
        stubHallQuery();

        assertEquals(1, orderService.hall(new OrderHallQuery()).getTotal());
    }

    @Test
    @DisplayName("大厅不返回已被指派的订单（收口 A · P0-1：查询须带 companion_id IS NULL）")
    void hallShouldExcludeAssignedOrders() {
        stubProfile(WorkStatus.AVAILABLE);
        stubHallQuery();

        orderService.hall(new OrderHallQuery());

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaQueryWrapper<CompanionOrder>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("IS NULL"),
                "指派单不进大厅：改派后 companion_id 非空的单必须被过滤，实际 SQL：" + sql);
    }

    @Test
    @DisplayName("休息中陪诊员抢单应返回 2013，且不产生任何订单写入")
    void restCompanionShouldBeRejectedFromAccept() {
        stubProfile(WorkStatus.REST);

        BusinessException ex = assertThrows(BusinessException.class, () -> orderService.accept(2001L));

        assertEquals(ResultCode.COMPANION_NOT_ACCEPTING.getCode(), ex.getCode());
        verify(orderMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("可接单陪诊员抢单仍成功（保护现状）")
    void availableCompanionShouldAcceptOrder() {
        stubProfile(WorkStatus.AVAILABLE);
        given(orderMapper.selectById(2001L)).willReturn(pendingOrder());

        assertNotNull(orderService.accept(2001L));
        verify(orderTransitionService).transition(any(), any(), any(), any());
    }

    @Test
    @DisplayName("下单广播只发给已审核且可接单的陪诊员（查询须带 work_status 条件）")
    void broadcastShouldOnlyIncludeAvailableCompanions() {
        loginAsFamily();
        given(elderService.requireAccessible(401L)).willReturn(elderProfile());
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.increment(anyString())).willReturn(7L);
        given(orderMapper.insert(any(CompanionOrder.class))).willAnswer(invocation -> {
            invocation.getArgument(0, CompanionOrder.class).setId(2001L);
            return 1;
        });
        // 只有 AVAILABLE 的档案会被广播查询命中
        stubProfile(WorkStatus.AVAILABLE);

        orderService.create(createDto());

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<LambdaQueryWrapper<CompanionProfile>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(companionProfileMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment();
        org.junit.jupiter.api.Assertions.assertTrue(sql.contains("work_status"),
                "广播查询必须按 work_status 过滤，实际 SQL：" + sql);
        verify(messageService).sendBatch(org.mockito.ArgumentMatchers.eq(List.of(COMPANION_ID)),
                any(), any(), any());
    }

    @Test
    @DisplayName("MatchingServiceImpl 对未知 workStatus 安全跳过（收敛裸字符串比较）")
    void matchingShouldSkipUnknownWorkStatus() {
        given(matchingMapper.selectCandidates()).willReturn(List.of(candidateRow("BUSY")));
        given(matchingMapper.selectSlots(any())).willReturn(List.of());
        given(matchingMapper.selectDepartmentOrderCounts(any())).willReturn(List.of());
        given(matchingMapper.selectInFlightOrders(any())).willReturn(List.of());
        MatchingServiceImpl matchingService = new MatchingServiceImpl(matchingMapper, new MatchingProperties());

        OrderRecommendVO result = matchingService.recommendCompanions(pendingOrder(), 5);

        assertEquals(0, result.getTotal(), "未知状态既不接单也不应被推荐");
    }

    /* ================================================================== */
    /* 夹具                                                                */
    /* ================================================================== */

    private void loginAsCompanion() {
        LoginUser loginUser = new LoginUser(COMPANION_ID, "companion301", RoleConstants.COMPANION, 0,
                "jti-p0-2", System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    private void stubProfile(WorkStatus workStatus) {
        stubProfileRaw(workStatus.name());
    }

    private void stubProfileRaw(String workStatus) {
        CompanionProfile profile = new CompanionProfile();
        profile.setId(601L);
        profile.setUserId(COMPANION_ID);
        profile.setRealName("李建军");
        profile.setAuditStatus(AuditStatus.APPROVED.name());
        profile.setWorkStatus(workStatus);
        profile.setServiceArea("美兰区");
        profile.setAvailableTime("周一至周五");
        profile.setScore(new BigDecimal("4.80"));
        given(companionProfileMapper.selectList(any())).willReturn(List.of(profile));
    }

    /** 大厅查询链路上的桩：拒过单列表 + 候选订单 + 老人档案 + Java 侧打分结果 */
    private void stubHallQuery() {
        given(rejectLogMapper.selectList(any())).willReturn(List.of());
        CompanionOrder order = new CompanionOrder();
        order.setId(2001L);
        order.setFamilyId(101L);
        order.setElderId(401L);
        order.setStatus(org.company.nianglin.constant.OrderStatus.PENDING.name());
        order.setVisitTime(java.time.LocalDateTime.now().plusDays(1));
        given(orderMapper.selectList(any())).willReturn(new java.util.ArrayList<>(List.of(order)));
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of());
    }

    private void loginAsFamily() {
        LoginUser loginUser = new LoginUser(101L, "family101", RoleConstants.FAMILY, 0,
                "jti-p0-2-family", System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    private CompanionOrder pendingOrder() {
        CompanionOrder order = new CompanionOrder();
        order.setId(2001L);
        order.setOrderNo("NL202611280001");
        order.setFamilyId(101L);
        order.setElderId(401L);
        order.setStatus(org.company.nianglin.constant.OrderStatus.PENDING.name());
        order.setVisitTime(LocalDateTime.now().plusDays(1));
        order.setFee(new BigDecimal("128.00"));
        order.setVersion(0);
        return order;
    }

    private ElderProfile elderProfile() {
        ElderProfile elder = new ElderProfile();
        elder.setId(401L);
        elder.setUserId(201L);
        elder.setName("测试老人");
        return elder;
    }

    private OrderCreateDTO createDto() {
        OrderCreateDTO dto = new OrderCreateDTO();
        dto.setElderId(401L);
        dto.setHospital("海南省人民医院");
        dto.setDepartment("心血管内科");
        dto.setVisitTime(LocalDateTime.now().plusDays(1));
        dto.setAddress("海南省海口市秀英区秀华路19号");
        return dto;
    }

    private Map<String, Object> candidateRow(String workStatus) {
        Map<String, Object> row = new HashMap<>();
        row.put("userId", COMPANION_ID);
        row.put("workStatus", workStatus);
        row.put("auditStatus", AuditStatus.APPROVED.name());
        return row;
    }
}
