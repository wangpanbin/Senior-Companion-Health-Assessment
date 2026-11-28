package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.company.nianglin.constant.FulfillmentEventType;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.OrderFulfillmentEvent;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.OrderFulfillmentEventMapper;
import org.company.nianglin.service.impl.OrderTimeoutServiceImpl;
import org.company.nianglin.service.support.OrderTransitionService;
import org.company.nianglin.support.MybatisLambdaCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 订单超时闭环（closure-2026-11 · P1-1）。
 *
 * <p>订单只有「有人在操作」时才会前进。本类盯的是两条超时出口：</p>
 * <ol>
 *   <li><b>待接单超时</b>：就诊时间过去 N 小时仍无人接单。默认只「标记」
 *       （风险升到 MANUAL_REQUIRED + 写事件 + 通知家属），自动取消是独立开关、
 *       默认关闭 —— 误伤「家属其实还想等服务」的订单，代价高于多挂一会儿。</li>
 *   <li><b>服务中超时</b>：进入服务中超过 N 小时仍未完成。只升级风险、<b>不改状态</b>
 *       —— 服务可能真的还在进行（医院排队、路上堵车），系统无权替人判断服务结束。</li>
 * </ol>
 *
 * <p>幂等靠「条件更新认领」：更新影响行 = 0 说明这条刚被另一实例处理过，
 * 于是不写事件、不发通知 —— 否则家属会收到重复告警。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("订单超时闭环：待接单超时 / 服务中超时")
class OrderTimeoutServiceTest {

    private static final Long ORDER_ID = 2001L;
    private static final Long FAMILY_ID = 101L;
    private static final Long COMPANION_ID = 301L;

    @Mock
    private CompanionOrderMapper orderMapper;
    @Mock
    private OrderFulfillmentEventMapper eventMapper;
    @Mock
    private MessageService messageService;
    @Mock
    private OrderTransitionService orderTransitionService;

    private OrderTimeoutServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new OrderTimeoutServiceImpl(orderMapper, eventMapper, messageService,
                orderTransitionService);
        ReflectionTestUtils.setField(service, "pendingTimeoutHours", 2);
        ReflectionTestUtils.setField(service, "inServiceTimeoutHours", 12);
        ReflectionTestUtils.setField(service, "pendingAutoCancel", false);
        ReflectionTestUtils.setField(service, "scanBatch", 200);
    }

    /* ================================================================== */
    /* A · 待接单超时                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("待接单超时 · 开关关闭时只标记：风险升到 MANUAL_REQUIRED，不改订单状态")
    void pendingTimeoutShouldOnlyMarkWhenAutoCancelOff() {
        CompanionOrder order = pendingOrder();
        given(orderMapper.selectList(any())).willReturn(List.of(order), List.of());
        given(orderMapper.update(any(), any())).willReturn(1);

        int handled = service.scanTimeouts();

        assertEquals(1, handled);
        verify(orderTransitionService, never()).forceTerminalAsSystem(any(), any(), any());
        assertEquals("MANUAL_REQUIRED", valueWrittenFor(captureRiskUpdate(), "fulfillment_risk"),
                "只标记时风险要升到「需要人工介入」，让管理员在风险中心看到");
        assertEquals(FulfillmentEventType.ORDER_TIMEOUT.name(), captureEvent().getEventType());
        verify(messageService).send(eq(FAMILY_ID), eq(MessageType.ORDER_TIMEOUT), eq(ORDER_ID), any());
    }

    @Test
    @DisplayName("待接单超时 · 开关打开时强制取消，取消原因写明「超时未接单」")
    void pendingTimeoutShouldCancelWhenAutoCancelOn() {
        ReflectionTestUtils.setField(service, "pendingAutoCancel", true);
        CompanionOrder order = pendingOrder();
        given(orderMapper.selectList(any())).willReturn(List.of(order), List.of());

        service.scanTimeouts();

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(orderTransitionService).forceTerminalAsSystem(eq(order), eq(OrderStatus.CANCELLED),
                reason.capture());
        assertTrue(reason.getValue().contains("就诊时间已过") && reason.getValue().contains("无人接单"),
                "取消原因必须让人一眼看出是超时自动取消：" + reason.getValue());
        assertTrue(order.getCancelTime() != null, "自动取消要留下取消时间");
        assertEquals(reason.getValue(), order.getCancelReason());
    }

    @Test
    @DisplayName("待接单超时 · 未过期 / 已完成的订单不进候选集")
    void pendingScanShouldOnlyLookAtPendingOrders() {
        given(orderMapper.selectList(any())).willReturn(List.of(), List.of());

        int handled = service.scanTimeouts();

        assertEquals(0, handled);
        verify(orderMapper, never()).update(any(), any());
        verify(eventMapper, never()).insert(any(OrderFulfillmentEvent.class));
        verify(messageService, never()).send(any(), any(), any(), any());
    }

    /* ================================================================== */
    /* B · 服务中超时                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("服务中超时 · 风险升到 URGENT + 写事件，但不改订单状态")
    void inServiceTimeoutShouldEscalateWithoutChangingStatus() {
        CompanionOrder order = inServiceOrder();
        given(orderMapper.selectList(any())).willReturn(List.of(), List.of(order));
        given(orderMapper.update(any(), any())).willReturn(1);

        int handled = service.scanTimeouts();

        assertEquals(1, handled);
        verify(orderTransitionService, never()).forceTerminalAsSystem(any(), any(), any());
        assertEquals("URGENT", valueWrittenFor(captureRiskUpdate(), "fulfillment_risk"));
        assertEquals(FulfillmentEventType.ORDER_IN_SERVICE_TIMEOUT.name(),
                captureEvent().getEventType());
        verify(messageService).send(eq(FAMILY_ID), eq(MessageType.ORDER_TIMEOUT), eq(ORDER_ID), any());
    }

    /* ================================================================== */
    /* C · 幂等与批量                                                       */
    /* ================================================================== */

    @Test
    @DisplayName("幂等 · 条件更新影响行 = 0（已被别的实例处理）时不写事件、不发通知")
    void repeatedScanShouldNotWriteEventTwice() {
        given(orderMapper.selectList(any())).willReturn(List.of(pendingOrder()), List.of());
        given(orderMapper.update(any(), any())).willReturn(0);

        int handled = service.scanTimeouts();

        assertEquals(0, handled);
        verify(eventMapper, never()).insert(any(OrderFulfillmentEvent.class));
        verify(messageService, never()).send(any(), any(), any(), any());
    }

    @Test
    @DisplayName("批量 · 单轮处理量受 scan-batch 上限约束")
    void scanShouldRespectBatchLimit() {
        ReflectionTestUtils.setField(service, "scanBatch", 2);
        given(orderMapper.selectList(any())).willReturn(
                List.of(pendingOrder(), pendingOrder(), pendingOrder()), List.of());
        given(orderMapper.update(any(), any())).willReturn(1);

        int handled = service.scanTimeouts();

        assertEquals(2, handled, "单轮处理量不能超过批次上限");
    }

    /* ================================================================== */

    private CompanionOrder pendingOrder() {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NO-2001");
        order.setFamilyId(FAMILY_ID);
        order.setCompanionId(null);
        order.setStatus(OrderStatus.PENDING.name());
        order.setVisitTime(LocalDateTime.now().minusHours(5));
        order.setFulfillmentRisk("NORMAL");
        return order;
    }

    private CompanionOrder inServiceOrder() {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NO-2001");
        order.setFamilyId(FAMILY_ID);
        order.setCompanionId(COMPANION_ID);
        order.setStatus(OrderStatus.IN_SERVICE.name());
        order.setStartTime(LocalDateTime.now().minusHours(20));
        order.setFulfillmentRisk("NORMAL");
        return order;
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<CompanionOrder> captureRiskUpdate() {
        ArgumentCaptor<LambdaUpdateWrapper<CompanionOrder>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(orderMapper).update(any(), captor.capture());
        return captor.getValue();
    }

    /** 从 SET 子句里按列名反查写入值（SET 里的值是占位符，真实值在参数表里） */
    private Object valueWrittenFor(LambdaUpdateWrapper<CompanionOrder> wrapper, String column) {
        String sqlSet = wrapper.getSqlSet();
        int columnAt = sqlSet.indexOf(column);
        if (columnAt < 0) {
            return null;
        }
        int keyStart = sqlSet.indexOf("MPGENVAL", columnAt);
        if (keyStart < 0) {
            return null;
        }
        int keyEnd = sqlSet.indexOf('}', keyStart);
        return wrapper.getParamNameValuePairs().get(sqlSet.substring(keyStart, keyEnd));
    }

    private OrderFulfillmentEvent captureEvent() {
        ArgumentCaptor<OrderFulfillmentEvent> captor = ArgumentCaptor.forClass(OrderFulfillmentEvent.class);
        verify(eventMapper).insert(captor.capture());
        return captor.getValue();
    }

    /** 候选集查询里用到的参数值（状态字面量等） */
    @SuppressWarnings("unused")
    private List<Object> queryParams(LambdaUpdateWrapper<CompanionOrder> wrapper) {
        wrapper.getSqlSegment();
        return new ArrayList<>(wrapper.getParamNameValuePairs().values());
    }

    @SuppressWarnings("unused")
    private Map<String, Object> emptyParams() {
        return Map.of();
    }
}
