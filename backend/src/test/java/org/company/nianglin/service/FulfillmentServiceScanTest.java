package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.company.nianglin.config.FulfillmentProperties;
import org.company.nianglin.constant.FulfillmentEventType;
import org.company.nianglin.constant.FulfillmentRisk;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.TriggerSource;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.OrderFulfillmentEvent;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.OrderFulfillmentEventMapper;
import org.company.nianglin.service.impl.FulfillmentServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 履约风险扫描单测（V3.1 · T2）。
 *
 * <p>本组测试保护三条最容易被写坏的不变量：</p>
 * <ol>
 *   <li><b>一次扫描只写一条事件</b> —— 订单从 NORMAL 连升到 MANUAL_REQUIRED 时，
 *       若按每一档各写一条，家属会在同一分钟收到三次告警；</li>
 *   <li><b>幂等</b> —— 连扫两轮，第二轮不能产生任何新事件；</li>
 *   <li><b>认领失败即静默跳过</b> —— 条件更新 rows=0（被别的实例抢先）时，
 *       不能写事件、不能发通知。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("履约风险扫描：认领 / 幂等 / 只写一条事件")
class FulfillmentServiceScanTest {

    @Mock
    private CompanionOrderMapper orderMapper;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

    @Mock
    private OrderFulfillmentEventMapper eventMapper;

    @Mock
    private MessageService messageService;

    /** 抓取扫描实际传给 Mapper 的分页大小 */
    private final AtomicLong capturedPageSize = new AtomicLong();

    private FulfillmentProperties properties;
    private FulfillmentService fulfillmentService;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        properties = new FulfillmentProperties();
        properties.setWarningMinutes(10);
        properties.setUrgentMinutes(30);
        properties.setManualRequiredMinutes(120);
        fulfillmentService = new FulfillmentServiceImpl(orderMapper, eventMapper,
                companionProfileMapper, messageService, properties);
    }

    /* ================================================================== */
    /* 单轮升级                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("等待超过预警线：等级升为 WARNING，写一条事件，发一条通知")
    void scanShouldEscalateToWarningAndWriteExactlyOneEvent() {
        givenCandidates(orderWaitingMinutes(15));
        givenClaimSucceeds();

        int escalated = fulfillmentService.scanAndEscalate();

        assertEquals(1, escalated);
        verify(eventMapper, times(1)).insert(any(OrderFulfillmentEvent.class));

        OrderFulfillmentEvent event = captureEvent();
        assertEquals(FulfillmentEventType.FULFILLMENT_WARNING.name(), event.getEventType());
        assertEquals(FulfillmentRisk.NORMAL.name(), event.getFromRisk());
        assertEquals(FulfillmentRisk.WARNING.name(), event.getToRisk());
        assertEquals(TriggerSource.SYSTEM.name(), event.getTriggerSource());

        verify(messageService, times(1)).send(eq(300L), eq(MessageType.FULFILLMENT_WARNING),
                eq(1001L), any());
    }

    /* ================================================================== */
    /* 连升只写一条                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("等待远超人工介入线：直接升到 MANUAL_REQUIRED，但事件仍然只有一条")
    void scanShouldEscalateOnceEvenWhenSkippingMultipleLevels() {
        givenCandidates(orderWaitingMinutes(150));
        givenClaimSucceeds();

        int escalated = fulfillmentService.scanAndEscalate();

        assertEquals(1, escalated);
        // 关键断言：连升三档只产生一条事件，而不是三条
        verify(eventMapper, times(1)).insert(any(OrderFulfillmentEvent.class));

        OrderFulfillmentEvent event = captureEvent();
        assertEquals(FulfillmentEventType.MANUAL_REQUIRED.name(), event.getEventType());
        assertEquals(FulfillmentRisk.NORMAL.name(), event.getFromRisk());
        assertEquals(FulfillmentRisk.MANUAL_REQUIRED.name(), event.getToRisk());

        // 人工介入由管理员侧兜底，不再重复打扰家属
        verify(messageService, never()).send(anyLong(), any(MessageType.class), anyLong(), any());
    }

    /* ================================================================== */
    /* 幂等                                                                */
    /* ================================================================== */

    @Test
    @DisplayName("第二轮扫描：库中等级已等于应有等级，不写事件也不发通知")
    void secondScanShouldBeIdempotent() {
        // 第一轮读到的还是 NORMAL，认领成功
        givenCandidates(orderWaitingMinutes(45));
        givenClaimSucceeds();
        assertEquals(1, fulfillmentService.scanAndEscalate());

        // 第二轮读到的已经是 URGENT（上一轮升上去的）
        givenCandidates(orderWaitingMinutes(45, FulfillmentRisk.URGENT));

        int second = fulfillmentService.scanAndEscalate();

        assertEquals(0, second);
        verify(eventMapper, times(1)).insert(any(OrderFulfillmentEvent.class));
        verify(messageService, times(1)).send(anyLong(), any(MessageType.class), anyLong(), any());
    }

    /* ================================================================== */
    /* 只升不降                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("库中等级高于应有等级（脏数据）时不降级，不写事件")
    void scanShouldNeverDowngrade() {
        // 等待 15 分钟（应为 WARNING），但库里是 URGENT
        givenCandidates(orderWaitingMinutes(15, FulfillmentRisk.URGENT));

        int escalated = fulfillmentService.scanAndEscalate();

        assertEquals(0, escalated);
        verify(eventMapper, never()).insert(any(OrderFulfillmentEvent.class));
        verify(orderMapper, never()).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    @DisplayName("等待时间未到任何阈值时不升级")
    void scanShouldIgnoreFreshOrders() {
        givenCandidates(orderWaitingMinutes(2));
        int escalated = fulfillmentService.scanAndEscalate();
        assertEquals(0, escalated);
        verify(eventMapper, never()).insert(any(OrderFulfillmentEvent.class));
    }

    /* ================================================================== */
    /* 认领失败                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("条件更新返回 0 行（被另一实例抢先）：静默跳过，不写事件不发通知")
    void scanShouldSkipWhenClaimLost() {
        givenCandidates(orderWaitingMinutes(15));
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(0);

        int escalated = fulfillmentService.scanAndEscalate();

        assertEquals(0, escalated);
        verify(eventMapper, never()).insert(any(OrderFulfillmentEvent.class));
        verify(messageService, never()).send(anyLong(), any(MessageType.class), anyLong(), any());
    }

    /* ================================================================== */
    /* 批次上限与空集                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("候选集为空时直接返回 0，不做任何写操作")
    void scanShouldReturnZeroWhenNoCandidates() {
        givenCandidates();

        assertEquals(0, fulfillmentService.scanAndEscalate());
        verify(eventMapper, never()).insert(any(OrderFulfillmentEvent.class));
    }

    @Test
    @DisplayName("单轮取数受 scan-batch 限制，超出的部分留给下一轮")
    void scanShouldHonourBatchLimit() {
        properties.setScanBatch(7);
        givenCandidates(orderWaitingMinutes(15));

        fulfillmentService.scanAndEscalate();

        // 用 Answer 抓取分页参数：BaseMapper#selectPage 是泛型方法，
        // ArgumentCaptor 在这条签名上推不出类型。
        assertEquals(7L, capturedPageSize.get());
    }

    /* ================================================================== */
    /* 认领语句必须带上旧等级                                              */
    /* ================================================================== */

    @Test
    @DisplayName("认领的条件更新按「读到的旧等级」而不是无条件写")
    void claimShouldUseOldRiskAsCondition() {
        // 库中已是 WARNING，但订单已等 45 分钟 → 应升到 URGENT。
        // 事件里的 fromRisk 若是 WARNING，就证明认领条件用的是实体读到的旧值，
        // 而不是无条件 set（无条件 set 的话 fromRisk 会是写死的 NORMAL）。
        givenCandidates(orderWaitingMinutes(45, FulfillmentRisk.WARNING));
        givenClaimSucceeds();

        int escalated = fulfillmentService.scanAndEscalate();

        assertEquals(1, escalated);
        OrderFulfillmentEvent event = captureEvent();
        assertEquals(FulfillmentRisk.WARNING.name(), event.getFromRisk());
        assertEquals(FulfillmentRisk.URGENT.name(), event.getToRisk());
    }

    /* ================================================================== */
    /* 下单登记                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("下单登记：写一条 ORDER_CREATED 事件，风险从空到 NORMAL")
    void registerCreatedShouldWriteInitialEvent() {
        fulfillmentService.registerCreated(1001L, 300L, "NL20261017000001");

        OrderFulfillmentEvent event = captureEvent();
        assertEquals(FulfillmentEventType.ORDER_CREATED.name(), event.getEventType());
        assertEquals(null, event.getFromRisk());
        assertEquals(FulfillmentRisk.NORMAL.name(), event.getToRisk());
        assertEquals(TriggerSource.SYSTEM.name(), event.getTriggerSource());
        assertEquals(300L, event.getOperatorId());
    }

    /* ================================================================== */
    /* 测试夹具                                                            */
    /* ================================================================== */

    private void givenCandidates(CompanionOrder... orders) {
        given(orderMapper.selectPage(any(Page.class), any())).willAnswer(invocation -> {
            Page<?> requested = invocation.getArgument(0);
            capturedPageSize.set(requested.getSize());
            Page<CompanionOrder> page = new Page<>(1L, requested.getSize());
            page.setRecords(List.of(orders));
            page.setTotal(orders.length);
            return page;
        });
    }

    private void givenClaimSucceeds() {
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(1);
    }

    private CompanionOrder orderWaitingMinutes(int minutes) {
        return orderWaitingMinutes(minutes, FulfillmentRisk.NORMAL);
    }

    private CompanionOrder orderWaitingMinutes(int minutes, FulfillmentRisk currentRisk) {
        CompanionOrder order = new CompanionOrder();
        order.setId(1001L);
        order.setOrderNo("NL20261017000001");
        order.setFamilyId(300L);
        order.setStatus(OrderStatus.PENDING.name());
        order.setFulfillmentRisk(currentRisk.name());
        order.setCreateTime(LocalDateTime.now().minusMinutes(minutes));
        return order;
    }

    private OrderFulfillmentEvent captureEvent() {
        ArgumentCaptor<OrderFulfillmentEvent> captor = ArgumentCaptor.forClass(OrderFulfillmentEvent.class);
        verify(eventMapper, times(1)).insert(captor.capture());
        return captor.getValue();
    }
}
