package org.company.nianglin.vo;

import org.company.nianglin.constant.FulfillmentRisk;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.entity.CompanionOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单出参的履约字段单测（V3.1 · T4）。
 *
 * <p>本组保护一条产品级不变量：<b>履约风险只在「待接单」时对外可见</b>。
 * 已接单的订单若还显示「等待时间较长」，家属会以为订单出了问题；
 * 已取消的订单若还显示风险，则是平台在展示一条已经作废的告警。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@DisplayName("OrderVO 履约字段：只读口径 + 只在待接单时生效")
class OrderVOFulfillmentTest {

    /* ================================================================== */
    /* 非待接单：履约字段一律收敛                                          */
    /* ================================================================== */

    @ParameterizedTest(name = "状态 [{0}] 时履约字段收敛")
    @ValueSource(strings = {"ACCEPTED", "IN_SERVICE", "COMPLETED", "REVIEWED", "CANCELLED"})
    @DisplayName("非待接单：风险恒为 NORMAL、等待时长为 0、不可催单")
    void nonPendingShouldCollapseFulfillmentFields(String status) {
        // 即使库里残留了高风险值（脏数据 / 状态刚被改掉），对外也不展示
        OrderVO vo = OrderVO.ofList(order(status, FulfillmentRisk.MANUAL_REQUIRED, 3, 200),
                "王*福", 82, "李*军");

        assertEquals(FulfillmentRisk.NORMAL.name(), vo.getFulfillmentRisk());
        assertEquals("正常等待", vo.getFulfillmentRiskLabel());
        assertEquals(0, vo.getWaitMinutes());
        assertFalse(vo.getCanUrge());
    }

    /* ================================================================== */
    /* 待接单：如实反映                                                    */
    /* ================================================================== */

    @Test
    @DisplayName("待接单 + 风险 URGENT：等待时长大于 0、可催单")
    void pendingUrgentShouldExposeWaitAndUrge() {
        OrderVO vo = OrderVO.ofList(order(OrderStatus.PENDING.name(), FulfillmentRisk.URGENT, 1, 45),
                "王*福", 82, null);

        assertEquals("URGENT", vo.getFulfillmentRisk());
        assertEquals("履约风险较高", vo.getFulfillmentRiskLabel());
        assertTrue(vo.getWaitMinutes() >= 45, "等待时长应至少 45 分钟，实际 " + vo.getWaitMinutes());
        assertEquals(1, vo.getUrgeCount());
        assertTrue(vo.getCanUrge());
    }

    @ParameterizedTest(name = "待接单 + 风险 [{0}] 不可催单")
    @ValueSource(strings = {"NORMAL", "WARNING"})
    @DisplayName("待接单但风险未达 URGENT：不可催单")
    void pendingLowRiskShouldNotAllowUrge(String risk) {
        OrderVO vo = OrderVO.ofList(order(OrderStatus.PENDING.name(), FulfillmentRisk.of(risk), 0, 15),
                "王*福", 82, null);

        assertFalse(vo.getCanUrge(),
                "风险为 " + risk + " 时前端不该看到「催促接单」按钮");
    }

    @Test
    @DisplayName("库里风险为 NULL 时按 NORMAL 处理（V6 之前的订单）")
    void nullRiskShouldFallBackToNormal() {
        CompanionOrder order = order(OrderStatus.PENDING.name(), null, 0, 30);
        order.setFulfillmentRisk(null);

        OrderVO vo = OrderVO.ofList(order, "王*福", 82, null);

        assertEquals(FulfillmentRisk.NORMAL.name(), vo.getFulfillmentRisk());
        assertNotNull(vo.getFulfillmentRiskLabel());
    }

    @Test
    @DisplayName("催单次数为 NULL 时返回 0，不返回 null")
    void nullUrgeCountShouldBecomeZero() {
        CompanionOrder order = order(OrderStatus.PENDING.name(), FulfillmentRisk.NORMAL, 0, 10);
        order.setUrgeCount(null);

        OrderVO vo = OrderVO.ofList(order, "王*福", 82, null);

        assertEquals(0, vo.getUrgeCount());
    }

    /* ================================================================== */
    /* 脏数据防御                                                          */
    /* ================================================================== */

    @Test
    @DisplayName("下单时间晚于当前（时钟漂移）时等待时长钳到 0，不返回负数")
    void futureCreateTimeShouldClampToZero() {
        CompanionOrder order = order(OrderStatus.PENDING.name(), FulfillmentRisk.NORMAL, 0, 10);
        order.setCreateTime(LocalDateTime.now().plusHours(2));

        OrderVO vo = OrderVO.ofList(order, "王*福", 82, null);

        assertEquals(0, vo.getWaitMinutes());
    }

    @Test
    @DisplayName("下单时间为 NULL 时等待时长为 0")
    void nullCreateTimeShouldYieldZeroWait() {
        CompanionOrder order = order(OrderStatus.PENDING.name(), FulfillmentRisk.NORMAL, 0, 10);
        order.setCreateTime(null);

        OrderVO vo = OrderVO.ofList(order, "王*福", 82, null);

        assertEquals(0, vo.getWaitMinutes());
    }

    /* ================================================================== */
    /* 既有字段不得受影响                                                  */
    /* ================================================================== */

    @Test
    @DisplayName("新增履约字段不改变既有字段的值（老前端不受影响）")
    void existingFieldsShouldBeUntouched() {
        OrderVO vo = OrderVO.ofList(order(OrderStatus.PENDING.name(), FulfillmentRisk.WARNING, 0, 20),
                "王*福", 82, "李*军");

        assertEquals("NL20261017000001", vo.getOrderNo());
        assertEquals("市第一人民医院", vo.getHospital());
        assertEquals("待接单", vo.getStatusLabel());
        assertEquals("128.00", vo.getFee());
        assertEquals("王*福", vo.getElderName());
        assertEquals("李*军", vo.getCompanionName());
    }

    @ParameterizedTest(name = "状态 [{0}] 风险 [{1}] → [{2}]")
    @CsvSource({
            "PENDING, NORMAL,   正常等待",
            "PENDING, WARNING,  等待时间较长",
            "PENDING, URGENT,   履约风险较高",
            "PENDING, MANUAL_REQUIRED, 需要人工介入",
    })
    @DisplayName("中文标签只从枚举取值")
    void riskLabelShouldComeFromEnum(String status, String risk, String expectedLabel) {
        OrderVO vo = OrderVO.ofList(order(status, FulfillmentRisk.of(risk), 0, 15), "王*福", 82, null);

        assertEquals(expectedLabel, vo.getFulfillmentRiskLabel());
    }

    /* ================================================================== */
    /* 测试夹具                                                            */
    /* ================================================================== */

    private CompanionOrder order(String status, FulfillmentRisk risk, int urgeCount, int waitedMinutes) {
        CompanionOrder order = new CompanionOrder();
        order.setId(1001L);
        order.setOrderNo("NL20261017000001");
        order.setFamilyId(300L);
        order.setElderId(501L);
        order.setCompanionId(status.equals(OrderStatus.PENDING.name()) ? null : 401L);
        order.setHospital("市第一人民医院");
        order.setDepartment("心内科");
        order.setStatus(status);
        order.setFulfillmentRisk(risk == null ? null : risk.name());
        order.setUrgeCount(urgeCount);
        order.setFee(new BigDecimal("128.00"));
        order.setCreateTime(LocalDateTime.now().minusMinutes(waitedMinutes));
        return order;
    }
}
