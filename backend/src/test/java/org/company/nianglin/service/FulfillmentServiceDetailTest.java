package org.company.nianglin.service;

import org.company.nianglin.config.FulfillmentProperties;
import org.company.nianglin.constant.FulfillmentEventType;
import org.company.nianglin.constant.FulfillmentRisk;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.TriggerSource;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.OrderFulfillmentEvent;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.OrderFulfillmentEventMapper;
import org.company.nianglin.service.impl.FulfillmentServiceImpl;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.OrderFulfillmentEventVO;
import org.company.nianglin.vo.OrderFulfillmentVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 履约详情装配单测（V3.1 · T4）。
 *
 * <p>两组不变量：<b>事件流必须升序</b>、<b>非待接单一律收敛</b>。
 * 前者是接口契约（前端直接把数组当时间线画，顺序错了就是因果颠倒），
 * 后者是产品口径（订单已经被接走了还提示「等待时间较长」，是在制造假告警）。</p>
 *
 * <p>升序特意用<b>打乱顺序的桩数据</b>验证：如果实现只依赖 SQL 的 ORDER BY，
 * 那么无论数组进数组出是什么样，这组用例都会绿——契约就等于没测。
 * 这里要求装配层自己再排一次，所以打乱输入必须能观察到升序输出。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("履约详情：事件流升序 + 只在待接单时暴露风险")
class FulfillmentServiceDetailTest {

    private static final Long ORDER_ID = 1001L;
    private static final LocalDateTime T0 = LocalDateTime.now().minusHours(2);

    @Mock
    private CompanionOrderMapper orderMapper;

    @Mock
    private OrderFulfillmentEventMapper eventMapper;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

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
    }

    /* ================================================================== */
    /* 事件流                                                              */
    /* ================================================================== */

    @Test
    @DisplayName("事件流按发生时间升序返回（打乱的桩数据也要排成升序）")
    void eventsShouldBeSortedAscending() {
        given(eventMapper.selectList(any())).willReturn(List.of(
                event(3L, FulfillmentEventType.FULFILLMENT_URGENT, T0.plusMinutes(40)),
                event(1L, FulfillmentEventType.ORDER_CREATED, T0),
                event(2L, FulfillmentEventType.FULFILLMENT_WARNING, T0.plusMinutes(15))));

        OrderFulfillmentVO vo = fulfillmentService.fulfillmentDetail(order(OrderStatus.PENDING.name()));

        assertEquals(List.of(FulfillmentEventType.ORDER_CREATED.name(),
                        FulfillmentEventType.FULFILLMENT_WARNING.name(),
                        FulfillmentEventType.FULFILLMENT_URGENT.name()),
                vo.getEvents().stream().map(OrderFulfillmentEventVO::getEventType).toList());
    }

    @Test
    @DisplayName("同一分钟内的事件按 ID 升序兜底（时间相同也要有个稳定次序）")
    void sameTimestampShouldFallBackToIdOrder() {
        given(eventMapper.selectList(any())).willReturn(List.of(
                event(9L, FulfillmentEventType.USER_URGED, T0.plusMinutes(20)),
                event(8L, FulfillmentEventType.FULFILLMENT_WARNING, T0.plusMinutes(20))));

        OrderFulfillmentVO vo = fulfillmentService.fulfillmentDetail(order(OrderStatus.PENDING.name()));

        assertEquals(List.of(FulfillmentEventType.FULFILLMENT_WARNING.name(),
                        FulfillmentEventType.USER_URGED.name()),
                vo.getEvents().stream().map(OrderFulfillmentEventVO::getEventType).toList(),
                "同秒事件里 ID 小的应排前面，否则时间线顺序会随机跳动");
    }

    @Test
    @DisplayName("发生时间为 null 的事件排到最后，不抛 NPE")
    void nullOperateTimeShouldSortLast() {
        given(eventMapper.selectList(any())).willReturn(List.of(
                event(7L, FulfillmentEventType.ORDER_CREATED, null),
                event(6L, FulfillmentEventType.MATCHING_STARTED, T0)));

        OrderFulfillmentVO vo = fulfillmentService.fulfillmentDetail(order(OrderStatus.PENDING.name()));

        assertEquals(2, vo.getEvents().size());
        assertEquals(FulfillmentEventType.MATCHING_STARTED.name(), vo.getEvents().get(0).getEventType());
        assertEquals(FulfillmentEventType.ORDER_CREATED.name(), vo.getEvents().get(1).getEventType());
    }

    @Test
    @DisplayName("无事件时返回空数组而不是 null（前端可直接 .length）")
    void noEventsShouldYieldEmptyListNotNull() {
        given(eventMapper.selectList(any())).willReturn(List.of());

        OrderFulfillmentVO vo = fulfillmentService.fulfillmentDetail(order(OrderStatus.PENDING.name()));

        assertNotNull(vo.getEvents());
        assertTrue(vo.getEvents().isEmpty());
    }

    @Test
    @DisplayName("事件类型与风险等级的中文文案全部由枚举翻译，不在装配处拼串")
    void labelsShouldComeFromEnum() {
        given(eventMapper.selectList(any())).willReturn(List.of(
                event(1L, FulfillmentEventType.ORDER_CREATED, T0),
                event(2L, FulfillmentEventType.FULFILLMENT_URGENT, T0.plusMinutes(40))));

        List<OrderFulfillmentEventVO> events =
                fulfillmentService.fulfillmentDetail(order(OrderStatus.PENDING.name())).getEvents();

        assertEquals("订单创建", events.get(0).getEventLabel());
        assertEquals("履约风险升高", events.get(1).getEventLabel());
        assertEquals("等待时间较长", events.get(1).getFromRiskLabel());
        assertEquals("履约风险较高", events.get(1).getToRiskLabel());
    }

    /* ================================================================== */
    /* 风险字段收敛                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("待接单 + 高风险：等待时长大于 0、可催单")
    void pendingHighRiskShouldExposeWaitAndUrge() {
        given(eventMapper.selectList(any())).willReturn(List.of());
        CompanionOrder order = order(OrderStatus.PENDING.name());
        order.setFulfillmentRisk(FulfillmentRisk.URGENT.name());
        order.setCreateTime(T0);

        OrderFulfillmentVO vo = fulfillmentService.fulfillmentDetail(order);

        assertEquals("URGENT", vo.getFulfillmentRisk());
        assertEquals("履约风险较高", vo.getFulfillmentRiskLabel());
        assertTrue(vo.getWaitMinutes() >= 120, "下单已 2 小时，等待时长应不少于 120");
        assertTrue(vo.getCanUrge());
    }

    @ParameterizedTest(name = "状态 [{0}] 时履约字段收敛")
    @ValueSource(strings = {"ACCEPTED", "IN_SERVICE", "COMPLETED", "REVIEWED", "CANCELLED"})
    @DisplayName("非待接单：等待时长归零、不可催单、无下次可催时间")
    void nonPendingShouldCollapse(String status) {
        given(eventMapper.selectList(any())).willReturn(List.of());
        CompanionOrder order = order(status);
        order.setFulfillmentRisk(FulfillmentRisk.MANUAL_REQUIRED.name());
        order.setUrgeCount(2);
        order.setLastUrgeAt(LocalDateTime.now().minusMinutes(1));
        order.setCreateTime(T0);

        OrderFulfillmentVO vo = fulfillmentService.fulfillmentDetail(order);

        assertEquals("NORMAL", vo.getFulfillmentRisk(),
                "非待接单的订单不允许携带风险等级，与 OrderVO#base 同一口径");
        assertEquals(0, vo.getWaitMinutes());
        assertFalse(vo.getCanUrge());
        assertNull(vo.getNextUrgeAvailableAt());
        // 催单次数是「发生过的事实」，即使现在不能催也不清零
        assertEquals(2, vo.getUrgeCount());
    }

    @Test
    @DisplayName("库里风险为 NULL（V6 之前的订单）按 NORMAL 处理")
    void nullRiskShouldFallBackToNormal() {
        given(eventMapper.selectList(any())).willReturn(List.of());
        CompanionOrder order = order(OrderStatus.PENDING.name());
        order.setFulfillmentRisk(null);

        OrderFulfillmentVO vo = fulfillmentService.fulfillmentDetail(order);

        assertEquals(FulfillmentRisk.NORMAL.name(), vo.getFulfillmentRisk());
        assertNotNull(vo.getFulfillmentRiskLabel());
    }

    @Test
    @DisplayName("催单次数为 NULL 时返回 0")
    void nullUrgeCountShouldBecomeZero() {
        given(eventMapper.selectList(any())).willReturn(List.of());
        CompanionOrder order = order(OrderStatus.PENDING.name());
        order.setUrgeCount(null);

        assertEquals(0, fulfillmentService.fulfillmentDetail(order).getUrgeCount());
    }

    /* ================================================================== */
    /* 下次可催时间                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("催过单且未达上限：下次可催时间 = 上次催促 + 冷却分钟")
    void nextUrgeTimeShouldBeLastUrgePlusCooldown() {
        given(eventMapper.selectList(any())).willReturn(List.of());
        CompanionOrder order = order(OrderStatus.PENDING.name());
        order.setFulfillmentRisk(FulfillmentRisk.URGENT.name());
        order.setUrgeCount(1);
        LocalDateTime lastUrge = LocalDateTime.now().minusMinutes(3);
        order.setLastUrgeAt(lastUrge);

        OrderFulfillmentVO vo = fulfillmentService.fulfillmentDetail(order);

        assertEquals(lastUrge.plusMinutes(10), vo.getNextUrgeAvailableAt());
    }

    @Test
    @DisplayName("已达次数上限：下次可催时间为 null（前端不再显示按钮）")
    void exhaustedQuotaShouldYieldNullNextUrgeTime() {
        given(eventMapper.selectList(any())).willReturn(List.of());
        CompanionOrder order = order(OrderStatus.PENDING.name());
        order.setFulfillmentRisk(FulfillmentRisk.URGENT.name());
        order.setUrgeCount(3);
        order.setLastUrgeAt(LocalDateTime.now().minusMinutes(30));

        assertNull(fulfillmentService.fulfillmentDetail(order).getNextUrgeAvailableAt());
    }

    @Test
    @DisplayName("从未催过单：没有冷却窗口，下次可催时间为 null")
    void neverUrgedShouldYieldNullNextUrgeTime() {
        given(eventMapper.selectList(any())).willReturn(List.of());
        CompanionOrder order = order(OrderStatus.PENDING.name());
        order.setFulfillmentRisk(FulfillmentRisk.URGENT.name());
        order.setUrgeCount(0);
        order.setLastUrgeAt(null);

        OrderFulfillmentVO vo = fulfillmentService.fulfillmentDetail(order);

        assertNull(vo.getNextUrgeAvailableAt());
        assertTrue(vo.getCanUrge(), "没有冷却窗口时应允许立即催促");
    }

    /* ================================================================== */
    /* VO 工厂的空值防御                                                   */
    /* ================================================================== */

    @Test
    @DisplayName("事件流传 null 时工厂仍返回空数组")
    void factoryShouldDefendNullEvents() {
        OrderFulfillmentVO.CompanionOrderSnapshot snapshot = new OrderFulfillmentVO.CompanionOrderSnapshot(
                ORDER_ID, "NL20261017000001", "PENDING", "待接单",
                "NORMAL", "正常等待", 5, 0, false, null);

        assertNotNull(OrderFulfillmentVO.of(snapshot, null).getEvents());
        assertTrue(OrderFulfillmentVO.of(snapshot, null).getEvents().isEmpty());
    }

    /* ================================================================== */
    /* 测试夹具                                                            */
    /* ================================================================== */

    private CompanionOrder order(String status) {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NL20261017000001");
        order.setFamilyId(300L);
        order.setElderId(501L);
        order.setStatus(status);
        order.setFulfillmentRisk(FulfillmentRisk.NORMAL.name());
        order.setUrgeCount(0);
        order.setCreateTime(T0);
        return order;
    }

    private OrderFulfillmentEvent event(Long id, FulfillmentEventType type, LocalDateTime operateTime) {
        OrderFulfillmentEvent e = new OrderFulfillmentEvent();
        e.setId(id);
        e.setOrderId(ORDER_ID);
        e.setEventType(type.name());
        e.setTriggerSource(TriggerSource.SYSTEM.name());
        e.setOperatorRole("SYSTEM");
        e.setOperateTime(operateTime);
        // 还原真实的升级链（正常 → 预警 → 较高 → 人工），否则标签断言测不出东西
        switch (type) {
            case ORDER_CREATED -> e.setToRisk(FulfillmentRisk.NORMAL.name());
            case FULFILLMENT_WARNING -> {
                e.setFromRisk(FulfillmentRisk.NORMAL.name());
                e.setToRisk(FulfillmentRisk.WARNING.name());
            }
            case FULFILLMENT_URGENT -> {
                e.setFromRisk(FulfillmentRisk.WARNING.name());
                e.setToRisk(FulfillmentRisk.URGENT.name());
            }
            case MANUAL_REQUIRED -> {
                e.setFromRisk(FulfillmentRisk.URGENT.name());
                e.setToRisk(FulfillmentRisk.MANUAL_REQUIRED.name());
            }
            default -> {
                // 催单 / 协调 / 改派不改风险，前后等级一致
            }
        }
        return e;
    }
}
