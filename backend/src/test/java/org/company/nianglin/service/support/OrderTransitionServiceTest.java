package org.company.nianglin.service.support;

import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 订单状态流转单一入口契约测试（ADR-0010）。
 *
 * <p>对应 `docs/adr/0010-order-state-transition-single-entry.md` 的验证标准。
 * 本测试存在的意义不是「测一个工具方法」，而是<b>让转移表被真正的业务路径使用</b>：
 * 只有当 {@code canTransitTo} 的判定结果真正决定落库与否时，
 * 删掉 {@code TRANSITIONS} 里的边才会让测试红。</p>
 *
 * @author 银龄伴诊团队
 * @see org.company.nianglin.constant.OrderStatus
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("订单状态流转单一入口（ADR-0010）")
class OrderTransitionServiceTest {

    @Mock
    private org.company.nianglin.mapper.CompanionOrderMapper orderMapper;

    @Mock
    private org.company.nianglin.mapper.OrderStatusLogMapper statusLogMapper;

    @Mock
    private org.company.nianglin.mapper.CompanionProfileMapper companionProfileMapper;

    @Mock
    private org.company.nianglin.mapper.SysUserMapper sysUserMapper;

    @InjectMocks
    private OrderTransitionServiceImpl transitionService;

    @BeforeEach
    void setUp() {
        // Mockito 默认返回 0，而 updateById 返回 0 = 乐观锁未命中 → 实现会抛
        // ORDER_ALREADY_TAKEN。先给「成功」默认值，需要失败分支的用例各自覆盖。
        org.mockito.Mockito.when(orderMapper.updateById(
                        org.mockito.ArgumentMatchers.<org.company.nianglin.entity.CompanionOrder>any()))
                .thenReturn(1);
    }

    private static CompanionOrder order(OrderStatus status) {
        CompanionOrder o = new CompanionOrder();
        o.setId(1001L);
        o.setStatus(status.name());
        o.setVersion(0);
        return o;
    }

    private static OrderTransitionService.Operator companion(long userId) {
        return OrderTransitionService.Operator.of(userId, RoleConstants.COMPANION, false);
    }

    private static OrderTransitionService.Operator family(long userId) {
        return OrderTransitionService.Operator.of(userId, RoleConstants.FAMILY, false);
    }

    @Test
    @DisplayName("合法正向流转：改状态 + version+1 + 写流转日志")
    void legalForwardTransitionShouldUpdateAndLog() {
        CompanionOrder order = order(OrderStatus.PENDING);

        transitionService.transition(order, OrderStatus.ACCEPTED, companion(301L), "已接单");

        assertEquals(OrderStatus.ACCEPTED.name(), order.getStatus());
        verify(orderMapper).updateById(org.mockito.ArgumentMatchers.<org.company.nianglin.entity.CompanionOrder>any());
        verify(statusLogMapper).insert(org.mockito.ArgumentMatchers.<org.company.nianglin.entity.OrderStatusLog>any());
    }

    @Test
    @DisplayName("禁止跳级：PENDING → COMPLETED 抛 3002 且不落库")
    void skipTransitionShouldBeRejected() {
        CompanionOrder order = order(OrderStatus.PENDING);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transitionService.transition(order, OrderStatus.COMPLETED, companion(301L), "跳级"));

        assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
        verify(orderMapper, never()).updateById(org.mockito.ArgumentMatchers.<org.company.nianglin.entity.CompanionOrder>any());
        verify(statusLogMapper, never()).insert(org.mockito.ArgumentMatchers.<org.company.nianglin.entity.OrderStatusLog>any());
    }

    @Test
    @DisplayName("禁止回退：ACCEPTED → PENDING 抛 3002")
    void backwardTransitionShouldBeRejected() {
        CompanionOrder order = order(OrderStatus.ACCEPTED);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transitionService.transition(order, OrderStatus.PENDING, family(101L), "回退"));

        assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("终态不可再流转：REVIEWED / CANCELLED 抛 3002")
    void terminalStatesShouldRejectAnyForwardTransition() {
        for (OrderStatus terminal : new OrderStatus[]{OrderStatus.REVIEWED, OrderStatus.CANCELLED}) {
            CompanionOrder order = order(terminal);
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> transitionService.transition(order, OrderStatus.ACCEPTED, companion(301L), "复活"),
                    terminal + " 是终态，必须拒绝");
            assertEquals(ResultCode.ORDER_STATUS_ILLEGAL.getCode(), ex.getCode());
        }
    }

    @Test
    @DisplayName("家属取消是独立合法路径：PENDING → CANCELLED 放行（不在正向表内）")
    void familyCancelShouldBeAllowedFromPending() {
        // 这条路径 AGENTS.md §4.1 明确要求由 canTransitTo 表达，
        // 但 TRANSITIONS 只承载正向流转（见 OrderStatus 注释），
        // 因此由 transition() 内部显式放行，而不是往表里塞边。
        CompanionOrder order = order(OrderStatus.PENDING);

        transitionService.transition(order, OrderStatus.CANCELLED, family(101L), "家属取消");

        assertEquals(OrderStatus.CANCELLED.name(), order.getStatus());
    }

    @Test
    @DisplayName("家属取消仅限 PENDING：已接单后家属无权单方取消（3004）")
    void familyCancelAfterAcceptedShouldBeRejected() {
        CompanionOrder order = order(OrderStatus.ACCEPTED);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transitionService.transition(order, OrderStatus.CANCELLED, family(101L), "家属取消"));

        assertEquals(ResultCode.ORDER_CANNOT_CANCEL.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("角色门禁：ELDER 不能推进订单状态")
    void elderShouldNotTriggerTransition() {
        CompanionOrder order = order(OrderStatus.PENDING);
        OrderTransitionService.Operator elder =
                OrderTransitionService.Operator.of(201L, RoleConstants.ELDER, false);

        assertThrows(BusinessException.class,
                () -> transitionService.transition(order, OrderStatus.ACCEPTED, elder, "老人越权"));
    }

    @Test
    @DisplayName("乐观锁未命中 → 抛 ORDER_ALREADY_TAKEN，不写日志")
    void optimisticLockMissShouldThrow() {
        CompanionOrder order = order(OrderStatus.PENDING);
        // 覆盖 setUp 里的成功默认值，模拟被别人抢先
        org.mockito.Mockito.when(orderMapper.updateById(
                        org.mockito.ArgumentMatchers.<org.company.nianglin.entity.CompanionOrder>any()))
                .thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transitionService.transition(order, OrderStatus.ACCEPTED, companion(301L), "已被抢先"));

        assertEquals(ResultCode.ORDER_ALREADY_TAKEN.getCode(), ex.getCode());
        verify(statusLogMapper, never()).insert(org.mockito.ArgumentMatchers.<org.company.nianglin.entity.OrderStatusLog>any());
    }

    @Test
    @DisplayName("已取消的订单再取消 → 3006 而不是 3002（取消路径优先于终态守卫）")
    void cancellingAlreadyCancelledShouldReturn3006Not3002() {
        // 回归用例：收口时曾把终态守卫放在取消判定之前，
        // 导致「已取消再取消」被拦成 3002。两者对家属含义完全不同 ——
        // 3006 告诉他「这单已经取消过了」，3002 会让他以为是状态机坏了。
        CompanionOrder order = order(OrderStatus.CANCELLED);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> transitionService.transition(order, OrderStatus.CANCELLED, family(101L), "再取消一次"));

        assertEquals(ResultCode.ORDER_CANNOT_CANCEL.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("流转日志双写：from/to/操作人/时间/是否强制 全部落齐")
    void statusLogShouldCarryFullContext() {
        CompanionOrder order = order(OrderStatus.IN_SERVICE);
        LocalDateTime before = LocalDateTime.now();

        transitionService.transition(order, OrderStatus.COMPLETED, companion(301L), "已完成");

        ArgumentCaptor<org.company.nianglin.entity.OrderStatusLog> captor =
                ArgumentCaptor.forClass(org.company.nianglin.entity.OrderStatusLog.class);
        verify(statusLogMapper).insert(captor.capture());
        org.company.nianglin.entity.OrderStatusLog row = captor.getValue();

        assertEquals(1001L, row.getOrderId());
        assertEquals(OrderStatus.IN_SERVICE.name(), row.getFromStatus());
        assertEquals(OrderStatus.COMPLETED.name(), row.getToStatus());
        assertEquals(301L, row.getOperatorId());
        assertEquals(RoleConstants.COMPANION, row.getOperatorRole());
        assertEquals(0, row.getIsForce());
        assertTrue(row.getOperateTime().isAfter(before.minusSeconds(1)));
    }
}
