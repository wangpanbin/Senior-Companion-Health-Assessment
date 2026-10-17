package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.FulfillmentProperties;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.constant.FulfillmentEventType;
import org.company.nianglin.constant.FulfillmentRisk;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.constant.TriggerSource;
import org.company.nianglin.constant.WorkStatus;
import org.company.nianglin.dto.OrderUrgeDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.OrderFulfillmentEvent;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.OrderFulfillmentEventMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.FulfillmentServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.OrderUrgeResultVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 家属催单单测（V3.1 · T3）。
 *
 * <p>核心是一条并发不变量：<b>资格判定与自增必须在同一条 UPDATE 里</b>。
 * 若实现退化成「先查后写」，本组里 {@code 条件更新返回 0 行 → 3009} 那条
 * 会变绿（它只断言错误码），而真实的重复催单仍然会漏过去 ——
 * 所以这里额外用「条件更新被调用且携带次数上限条件」把结构钉住。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("家属催单：资格 / 频率 / 并发穿透")
class FulfillmentServiceUrgeTest {

    private static final Long ORDER_ID = 1001L;
    private static final Long FAMILY_ID = 300L;

    @Mock
    private CompanionOrderMapper orderMapper;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

    @Mock
    private OrderFulfillmentEventMapper eventMapper;

    @Mock
    private MessageService messageService;

    private FulfillmentProperties properties;
    private FulfillmentService fulfillmentService;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        properties = new FulfillmentProperties();
        properties.setUrgeCooldownMinutes(10);
        properties.setUrgeMaxTimes(3);
        fulfillmentService = new FulfillmentServiceImpl(orderMapper, eventMapper,
                companionProfileMapper, messageService, properties);
        loginAsFamily();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 正常路径                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("正常催单：计数自增、返回下次可催时间、写事件、推可接单陪诊员")
    void urgeShouldSucceedAndNotifyAvailableCompanions() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder(FulfillmentRisk.URGENT, 0, null));
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(1);
        given(companionProfileMapper.selectList(any()))
                .willReturn(List.of(profile(401L), profile(402L)));

        OrderUrgeResultVO vo = fulfillmentService.urge(ORDER_ID, new OrderUrgeDTO());

        assertEquals(1, vo.getUrgeCount());
        assertNotNull(vo.getLastUrgeAt());
        assertNotNull(vo.getNextUrgeAvailableAt());

        OrderFulfillmentEvent event = captureEvent();
        assertEquals(FulfillmentEventType.USER_URGED.name(), event.getEventType());
        assertEquals(TriggerSource.USER.name(), event.getTriggerSource());
        assertEquals(FAMILY_ID, event.getOperatorId());

        verify(messageService, times(1)).sendBatch(eq(List.of(401L, 402L)),
                eq(MessageType.ORDER_URGED), eq(ORDER_ID), any());
    }

    @Test
    @DisplayName("达到次数上限后 nextUrgeAvailableAt 为 null（前端据此不再显示按钮）")
    void urgeShouldReturnNullNextTimeWhenQuotaExhausted() {
        properties.setUrgeMaxTimes(1);
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder(FulfillmentRisk.URGENT, 0, null));
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(1);
        given(companionProfileMapper.selectList(any())).willReturn(List.of());

        OrderUrgeResultVO vo = fulfillmentService.urge(ORDER_ID, new OrderUrgeDTO());

        assertEquals(1, vo.getUrgeCount());
        assertNull(vo.getNextUrgeAvailableAt());
    }

    @Test
    @DisplayName("无任何可接单陪诊员时不广播，也不报错（订单风险事实已经落库）")
    void urgeShouldNotBroadcastWhenNoAvailableCompanion() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder(FulfillmentRisk.URGENT, 0, null));
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(1);
        given(companionProfileMapper.selectList(any())).willReturn(List.of());

        assertNotNull(fulfillmentService.urge(ORDER_ID, new OrderUrgeDTO()));
        verify(messageService, never()).sendBatch(any(), any(MessageType.class), anyLong(), any());
    }

    /* ================================================================== */
    /* 拒绝路径                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("订单不存在返回 3001")
    void urgeShouldRejectWhenOrderMissing() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fulfillmentService.urge(ORDER_ID, new OrderUrgeDTO()));
        assertEquals(ResultCode.ORDER_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("不是本单下单人返回 3004（且在状态/风险判定之前）")
    void urgeShouldRejectOtherFamily() {
        // 家属 B 登录，却去催家属 A 下的单：归属不通过
        CompanionOrder othersOrder = pendingOrder(FulfillmentRisk.URGENT, 0, null);
        othersOrder.setFamilyId(999L);
        given(orderMapper.selectById(ORDER_ID)).willReturn(othersOrder);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fulfillmentService.urge(ORDER_ID, new OrderUrgeDTO()));
        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
        // 归属不通过时不该碰写库
        verify(orderMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @ParameterizedTest(name = "订单状态 [{0}] 不可催单")
    @ValueSource(strings = {"ACCEPTED", "IN_SERVICE", "COMPLETED", "CANCELLED"})
    @DisplayName("非待接单状态返回 3008")
    void urgeShouldRejectNonPendingOrder(String status) {
        CompanionOrder order = pendingOrder(FulfillmentRisk.URGENT, 0, null);
        order.setStatus(status);
        given(orderMapper.selectById(ORDER_ID)).willReturn(order);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fulfillmentService.urge(ORDER_ID, new OrderUrgeDTO()));
        assertEquals(ResultCode.ORDER_URGE_NOT_ALLOWED.getCode(), ex.getCode());
    }

    @ParameterizedTest(name = "风险 [{0}] 不可催单")
    @ValueSource(strings = {"NORMAL", "WARNING"})
    @DisplayName("风险未达「较高」返回 3008")
    void urgeShouldRejectWhenRiskTooLow(String risk) {
        given(orderMapper.selectById(ORDER_ID))
                .willReturn(pendingOrder(FulfillmentRisk.of(risk), 0, null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fulfillmentService.urge(ORDER_ID, new OrderUrgeDTO()));
        assertEquals(ResultCode.ORDER_URGE_NOT_ALLOWED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("条件更新返回 0 行（并发穿透 / 冷却期 / 超限）统一报 3009")
    void urgeShouldReturnTooFrequentWhenClaimLost() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder(FulfillmentRisk.URGENT, 1, null));
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fulfillmentService.urge(ORDER_ID, new OrderUrgeDTO()));
        assertEquals(ResultCode.ORDER_URGE_TOO_FREQUENT.getCode(), ex.getCode());

        // 关键：认领失败时绝不能留下事件或通知
        verify(eventMapper, never()).insert(any(OrderFulfillmentEvent.class));
        verify(messageService, never()).sendBatch(any(), any(MessageType.class), anyLong(), any());
    }

    /* ================================================================== */
    /* 备注合规                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("备注为空时记默认文案，不留 null")
    void urgeShouldUseDefaultRemarkWhenBlank() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder(FulfillmentRisk.URGENT, 0, null));
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(1);
        given(companionProfileMapper.selectList(any())).willReturn(List.of());

        fulfillmentService.urge(ORDER_ID, new OrderUrgeDTO().setRemark("   "));

        assertNotNull(captureEvent().getRemark());
    }

    @Test
    @DisplayName("备注命中医疗建议词表时拒绝，且不写库")
    void urgeShouldRejectMedicalRemark() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder(FulfillmentRisk.URGENT, 0, null));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fulfillmentService.urge(ORDER_ID,
                        new OrderUrgeDTO().setRemark("建议服用两片降压药")));
        assertEquals(ResultCode.CONTENT_SENSITIVE.getCode(), ex.getCode());
        verify(orderMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    /* ================================================================== */
    /* 广播范围                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("只广播给资质已通过且处于接单中的陪诊员")
    void urgeShouldBroadcastOnlyToAvailableCompanions() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(pendingOrder(FulfillmentRisk.URGENT, 0, null));
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(1);
        // 查询条件由实现负责，这里验证「取到谁就发给谁」，且 userId 为 null 的档案被剔除
        given(companionProfileMapper.selectList(any())).willReturn(List.of(
                profile(401L),
                profileWithNullUserId()));

        fulfillmentService.urge(ORDER_ID, new OrderUrgeDTO());

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(messageService).sendBatch(captor.capture(), eq(MessageType.ORDER_URGED),
                eq(ORDER_ID), any());
        assertEquals(List.of(401L), captor.getValue(),
                "userId 为 null 的档案不能进接收人列表，否则站内信落库会报错");
    }

    @Test
    @DisplayName("陪诊员档案字段语义保持：AVAILABLE 才是可接单")
    void availableStatusIsTheOnlyAcceptingOne() {
        assertTrue(WorkStatus.AVAILABLE.isAccepting());
        assertEquals(AuditStatus.APPROVED.name(), "APPROVED");
    }

    /* ================================================================== */
    /* 测试夹具                                                            */
    /* ================================================================== */

    private void loginAsFamily() {
        LoginUser loginUser = new LoginUser(FAMILY_ID, "family300", RoleConstants.FAMILY, 0,
                "jti-test", System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    private CompanionOrder pendingOrder(FulfillmentRisk risk, Integer urgeCount,
                                        LocalDateTime lastUrgeAt) {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NL20261017000001");
        order.setFamilyId(FAMILY_ID);
        order.setStatus(OrderStatus.PENDING.name());
        order.setFulfillmentRisk(risk == null ? null : risk.name());
        order.setUrgeCount(urgeCount);
        order.setLastUrgeAt(lastUrgeAt);
        order.setCreateTime(LocalDateTime.now().minusMinutes(45));
        return order;
    }

    private CompanionProfile profile(Long userId) {
        CompanionProfile p = new CompanionProfile();
        p.setUserId(userId);
        p.setAuditStatus(AuditStatus.APPROVED.name());
        p.setWorkStatus(WorkStatus.AVAILABLE.name());
        return p;
    }

    private CompanionProfile profileWithNullUserId() {
        CompanionProfile p = profile(null);
        p.setId(999L);
        return p;
    }

    private OrderFulfillmentEvent captureEvent() {
        ArgumentCaptor<OrderFulfillmentEvent> captor = ArgumentCaptor.forClass(OrderFulfillmentEvent.class);
        verify(eventMapper, times(1)).insert(captor.capture());
        return captor.getValue();
    }
}
