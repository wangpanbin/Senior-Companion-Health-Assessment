package org.company.nianglin.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单状态机单测。
 *
 * <p>对应 plan.md · M4 的验收标准：</p>
 * <ul>
 *   <li>禁止跳级（待接单不能直接到已完成）</li>
 *   <li>禁止回退（已接单不能退回待接单）</li>
 *   <li>终态不可再流转</li>
 * </ul>
 *
 * @author 银龄伴诊团队
 */
@DisplayName("订单状态机")
class OrderStatusTest {

    @Test
    @DisplayName("正向流转：每一步都合法")
    void forwardTransitionShouldBeAllowed() {
        assertTrue(OrderStatus.PENDING.canTransitTo(OrderStatus.ACCEPTED));
        assertTrue(OrderStatus.ACCEPTED.canTransitTo(OrderStatus.IN_SERVICE));
        assertTrue(OrderStatus.IN_SERVICE.canTransitTo(OrderStatus.COMPLETED));
        assertTrue(OrderStatus.COMPLETED.canTransitTo(OrderStatus.REVIEWED));
    }

    @Test
    @DisplayName("禁止跳级：待接单不能直接变已完成 / 已评价")
    void skipShouldBeRejected() {
        assertFalse(OrderStatus.PENDING.canTransitTo(OrderStatus.IN_SERVICE));
        assertFalse(OrderStatus.PENDING.canTransitTo(OrderStatus.COMPLETED));
        assertFalse(OrderStatus.PENDING.canTransitTo(OrderStatus.REVIEWED));
        assertFalse(OrderStatus.ACCEPTED.canTransitTo(OrderStatus.COMPLETED));
    }

    @Test
    @DisplayName("禁止回退：已接单不能退回待接单")
    void rollbackShouldBeRejected() {
        assertFalse(OrderStatus.ACCEPTED.canTransitTo(OrderStatus.PENDING));
        assertFalse(OrderStatus.IN_SERVICE.canTransitTo(OrderStatus.ACCEPTED));
        assertFalse(OrderStatus.COMPLETED.canTransitTo(OrderStatus.IN_SERVICE));
        assertFalse(OrderStatus.REVIEWED.canTransitTo(OrderStatus.COMPLETED));
    }

    @Test
    @DisplayName("终态不可再正向流转")
    void terminalStatusShouldNotTransit() {
        assertTrue(OrderStatus.REVIEWED.isTerminal());
        assertTrue(OrderStatus.CANCELLED.isTerminal());
        for (OrderStatus target : OrderStatus.values()) {
            assertFalse(OrderStatus.REVIEWED.canTransitTo(target));
            assertFalse(OrderStatus.CANCELLED.canTransitTo(target));
        }
    }

    @Test
    @DisplayName("取消订单不在任何正向流转中（只能由管理员通过纠纷处理触发）")
    void cancelIsNotAForwardTransition() {
        assertFalse(OrderStatus.PENDING.canTransitTo(OrderStatus.CANCELLED));
        assertFalse(OrderStatus.ACCEPTED.canTransitTo(OrderStatus.CANCELLED));
        assertFalse(OrderStatus.IN_SERVICE.canTransitTo(OrderStatus.CANCELLED));
    }

    @Test
    @DisplayName("目标状态为 null 时应安全返回 false")
    void nullTargetShouldBeSafe() {
        assertFalse(OrderStatus.PENDING.canTransitTo(null));
    }

    @Test
    @DisplayName("按名称 / 中文标签反查")
    void shouldResolveByNameOrLabel() {
        assertEquals(OrderStatus.PENDING, OrderStatus.of("PENDING"));
        assertEquals(OrderStatus.PENDING, OrderStatus.of("pending"));
        assertEquals(OrderStatus.PENDING, OrderStatus.of("待接单"));
        assertEquals(OrderStatus.REVIEWED, OrderStatus.of("已评价"));
        assertNull(OrderStatus.of("不存在"));
        assertNull(OrderStatus.of(null));
    }

    @Test
    @DisplayName("管理员可强制进入的终态只有 已完成 / 已取消")
    void onlyAdminForceableTerminals() {
        assertTrue(OrderStatus.CANCELLED.isAdminForceable());
        assertTrue(OrderStatus.COMPLETED.isAdminForceable());
        assertFalse(OrderStatus.PENDING.isAdminForceable());
        assertFalse(OrderStatus.REVIEWED.isAdminForceable());
    }
}
