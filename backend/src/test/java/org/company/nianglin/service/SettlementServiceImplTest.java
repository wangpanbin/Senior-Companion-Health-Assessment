package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.OperTargetType;
import org.company.nianglin.constant.OperType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.PaymentStatus;
import org.company.nianglin.dto.AdminSettlementQuery;
import org.company.nianglin.dto.SettlementBatchConfirmDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.OrderReadMapper;
import org.company.nianglin.service.impl.SettlementServiceImpl;
import org.company.nianglin.service.support.OperLogRecorder;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.SettlementBatchConfirmVO;
import org.company.nianglin.vo.SettlementExportVO;
import org.company.nianglin.vo.SettlementOrderVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 结算核对单测（V4 · P2-1）。
 *
 * <p>本组锁住四条性质：</p>
 *
 * <ol>
 *   <li><b>幂等</b>——已结算重复核定返回成功且 {@code settledNow=false}，
 *       更新与留痕都不再发生；</li>
 *   <li><b>前置条件</b>——只有 {@code COMPLETED / REVIEWED} 可结算，
 *       其余状态一律 {@code 8004}，不能出现「取消单也被核了」；</li>
 *   <li><b>金额只读</b>——{@code actual_fee} 已有值就保留，为空才快照 {@code fee}，
 *       接口永远不接受前端金额；</li>
 *   <li><b>逐单留痕</b>——批量里只有真正变更的订单各写一条日志。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-1
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("结算核对：幂等 / 前置条件 / 金额快照 / 逐单留痕")
class SettlementServiceImplTest {

    private static final Long ORDER_ID = 1001L;
    private static final String ORDER_NO = "NL20260915000001";

    @Mock
    private CompanionOrderMapper orderMapper;
    @Mock
    private OrderReadMapper orderReadMapper;
    @Mock
    private UserNameResolver userNameResolver;
    @Mock
    private OperLogRecorder operLogRecorder;
    @Mock
    private ExportService exportService;

    private SettlementServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new SettlementServiceImpl(orderMapper, orderReadMapper, userNameResolver,
                operLogRecorder, exportService);
        // 与既有订单导出共用同一配置项，测试里不依赖 Spring 环境
        ReflectionTestUtils.setField(service, "maxExportRows", 10000);
    }

    /* ================================================================== */
    /* 1 · 正常核定                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("未结算 + 已完成 → 置为已结算、快照金额并写一条日志")
    void confirmShouldSettleAndLogOnce() {
        CompanionOrder unpaid = order(ORDER_ID, OrderStatus.COMPLETED.name(),
                PaymentStatus.UNPAID.name(), new BigDecimal("120.00"), null);
        CompanionOrder settled = order(ORDER_ID, OrderStatus.COMPLETED.name(),
                PaymentStatus.SETTLED.name(), new BigDecimal("120.00"), new BigDecimal("120.00"));

        given(orderMapper.selectById(ORDER_ID)).willReturn(unpaid, settled);
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(1);

        var vo = service.confirm(ORDER_ID);

        assertTrue(vo.getSettledNow(), "首次核定 settledNow 必须为 true");
        assertEquals(PaymentStatus.SETTLED.name(), vo.getPaymentStatus());
        assertEquals("120.00", vo.getSettleAmount());
        assertEquals(ORDER_NO, vo.getOrderNo());

        verify(operLogRecorder, times(1)).record(
                eq(OperType.SETTLE_ORDER), eq(OperTargetType.ORDER), eq(ORDER_ID), eq(ORDER_NO),
                eq(PaymentStatus.UNPAID.name()), eq(PaymentStatus.SETTLED.name()), any());

        ArgumentCaptor<LambdaUpdateWrapper<CompanionOrder>> captor = wrapperCaptor();
        verify(orderMapper, times(1)).update(any(), captor.capture());
        String sqlSet = String.valueOf(captor.getValue().getSqlSet());
        assertTrue(sqlSet.contains("actual_fee"), "核定时必须快照 actual_fee");
        assertTrue(sqlSet.contains("COALESCE"), "已存在的 actual_fee 不能被覆盖");
    }

    @Test
    @DisplayName("已结算重复核定 → 200 + settledNow=false，不更新也不再写日志")
    void repeatedConfirmShouldBeIdempotent() {
        CompanionOrder settled = order(ORDER_ID, OrderStatus.REVIEWED.name(),
                PaymentStatus.SETTLED.name(), new BigDecimal("100.00"), new BigDecimal("80.00"));
        given(orderMapper.selectById(ORDER_ID)).willReturn(settled);

        var vo = service.confirm(ORDER_ID);

        assertFalse(vo.getSettledNow());
        assertEquals("80.00", vo.getSettleAmount(), "已结算单应展示快照后的实际金额");
        verify(orderMapper, never()).update(any(), any());
        verify(operLogRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    /* ================================================================== */
    /* 2 · 前置条件                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("未完成订单 → 8004，不更新、不写日志（取消单永不进入结算）")
    void nonCompletedOrderShouldBeRejected() {
        CompanionOrder pending = order(ORDER_ID, OrderStatus.PENDING.name(),
                PaymentStatus.UNPAID.name(), new BigDecimal("120.00"), null);
        given(orderMapper.selectById(ORDER_ID)).willReturn(pending);

        BusinessException e = assertThrows(BusinessException.class, () -> service.confirm(ORDER_ID));

        assertEquals(ResultCode.SETTLEMENT_STATUS_ILLEGAL.getCode(), e.getCode());
        verify(orderMapper, never()).update(any(), any());
        verify(operLogRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    /**
     * P0-5：判定顺序修正。
     *
     * <p>旧顺序是「先判已结算（幂等返回）→ 再判状态可结算」，于是「已结算 + 已取消」
     * 的订单再点核定会拿到 <b>假成功</b>（200 + 已结算），而这张单在台账里根本不存在 ——
     * 管理员以为核定了，实际上什么都没发生，且再也看不到它。</p>
     */
    @Test
    @DisplayName("已结算 + 已取消的订单再核定 → 8004（不能再是假成功）")
    void settledButCancelledOrderShouldBeRejected() {
        CompanionOrder settledCancelled = order(ORDER_ID, OrderStatus.CANCELLED.name(),
                PaymentStatus.SETTLED.name(), new BigDecimal("120.00"), new BigDecimal("120.00"));
        given(orderMapper.selectById(ORDER_ID)).willReturn(settledCancelled);

        BusinessException e = assertThrows(BusinessException.class, () -> service.confirm(ORDER_ID));

        assertEquals(ResultCode.SETTLEMENT_STATUS_ILLEGAL.getCode(), e.getCode());
        verify(orderMapper, never()).update(any(), any());
        verify(operLogRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("订单不存在 → 3001")
    void missingOrderShouldBeNotFound() {
        given(orderMapper.selectById(ORDER_ID)).willReturn(null);

        BusinessException e = assertThrows(BusinessException.class, () -> service.confirm(ORDER_ID));

        assertEquals(ResultCode.ORDER_NOT_FOUND.getCode(), e.getCode());
        verify(orderMapper, never()).update(any(), any());
    }

    @Test
    @DisplayName("条件更新 0 行且回查仍是未结算（并发下状态已变） → 8004")
    void updateZeroRowsAndStillUnpaidShouldBeIllegalStatus() {
        CompanionOrder unpaidPending = order(ORDER_ID, OrderStatus.PENDING.name(),
                PaymentStatus.UNPAID.name(), new BigDecimal("120.00"), null);
        // 第一次读到的是 COMPLETED，但 update 时状态已被改成 PENDING，回查得到 PENDING
        CompanionOrder completed = order(ORDER_ID, OrderStatus.COMPLETED.name(),
                PaymentStatus.UNPAID.name(), new BigDecimal("120.00"), null);
        given(orderMapper.selectById(ORDER_ID)).willReturn(completed, unpaidPending);
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(0);

        BusinessException e = assertThrows(BusinessException.class, () -> service.confirm(ORDER_ID));

        assertEquals(ResultCode.SETTLEMENT_STATUS_ILLEGAL.getCode(), e.getCode());
        verify(operLogRecorder, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    /* ================================================================== */
    /* 3 · 金额                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("actual_fee 已有值时保留，不被 fee 覆盖")
    void existingActualFeeShouldBeKept() {
        CompanionOrder unpaid = order(ORDER_ID, OrderStatus.COMPLETED.name(),
                PaymentStatus.UNPAID.name(), new BigDecimal("120.00"), new BigDecimal("100.00"));
        CompanionOrder settled = order(ORDER_ID, OrderStatus.COMPLETED.name(),
                PaymentStatus.SETTLED.name(), new BigDecimal("120.00"), new BigDecimal("100.00"));
        given(orderMapper.selectById(ORDER_ID)).willReturn(unpaid, settled);
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(1);

        var vo = service.confirm(ORDER_ID);

        assertEquals("100.00", vo.getSettleAmount());
    }

    /* ================================================================== */
    /* 4 · 批量                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("批量混合：去重保序、逐项成败、只对真正变更的单写日志")
    void batchShouldReportEachItemAndLogOnlyChangedOnes() {
        CompanionOrder unpaid = order(1001L, OrderStatus.COMPLETED.name(),
                PaymentStatus.UNPAID.name(), new BigDecimal("120.00"), null);
        CompanionOrder settledOne = order(1001L, OrderStatus.COMPLETED.name(),
                PaymentStatus.SETTLED.name(), new BigDecimal("120.00"), new BigDecimal("120.00"));
        CompanionOrder already = order(1002L, OrderStatus.REVIEWED.name(),
                PaymentStatus.SETTLED.name(), new BigDecimal("80.00"), new BigDecimal("80.00"));
        CompanionOrder invalid = order(1003L, OrderStatus.PENDING.name(),
                PaymentStatus.UNPAID.name(), new BigDecimal("60.00"), null);

        given(orderMapper.selectById(1001L)).willReturn(unpaid, settledOne);
        given(orderMapper.selectById(1002L)).willReturn(already);
        given(orderMapper.selectById(1003L)).willReturn(invalid);
        given(orderMapper.selectById(1004L)).willReturn(null);
        given(orderMapper.update(any(), any(LambdaUpdateWrapper.class))).willReturn(1);

        SettlementBatchConfirmVO result = service.batchConfirm(batchDto(1001L, 1001L, 1002L, 1003L, 1004L));

        assertEquals(4, result.getTotal().intValue(), "重复 ID 必须去重");
        assertEquals(1, result.getSettledCount().intValue());
        assertEquals(1, result.getAlreadySettledCount().intValue());
        assertEquals(2, result.getFailedCount().intValue());
        assertEquals(4, result.getItems().size());

        assertTrue(result.getItems().get(0).getSettledNow());
        assertEquals("已核定", result.getItems().get(0).getMessage());
        assertFalse(result.getItems().get(1).getSettledNow());
        assertEquals("该单已结算", result.getItems().get(1).getMessage());
        assertEquals(ResultCode.SETTLEMENT_STATUS_ILLEGAL.getCode(), result.getItems().get(2).getCode());
        assertEquals(ResultCode.ORDER_NOT_FOUND.getCode(), result.getItems().get(3).getCode());

        verify(operLogRecorder, times(1)).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("批量空列表 → 400")
    void emptyBatchShouldBeParamError() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.batchConfirm(batchDto()));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), e.getCode());
        verify(orderMapper, never()).update(any(), any());
    }

    /* ================================================================== */
    /* 5 · 导出                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("导出超限 → 9001，先校验再写 Excel")
    void exportOverLimitShouldThrowBeforeWriting() {
        ReflectionTestUtils.setField(service, "maxExportRows", 2);
        given(orderMapper.selectList(any())).willReturn(List.of(
                order(1L, OrderStatus.COMPLETED.name(), PaymentStatus.UNPAID.name(), new BigDecimal("1.00"), null),
                order(2L, OrderStatus.COMPLETED.name(), PaymentStatus.UNPAID.name(), new BigDecimal("1.00"), null),
                order(3L, OrderStatus.COMPLETED.name(), PaymentStatus.UNPAID.name(), new BigDecimal("1.00"), null)));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.export(new AdminSettlementQuery(), mock(HttpServletResponse.class)));

        assertEquals(ResultCode.EXPORT_LIMIT_EXCEEDED.getCode(), e.getCode());
        verify(exportService, never()).writeExcel(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("导出行：金额两位小数、姓名脱敏、无手机号字段")
    void exportRowsShouldBeMaskedAndFormatted() {
        CompanionOrder order = order(ORDER_ID, OrderStatus.COMPLETED.name(),
                PaymentStatus.UNPAID.name(), new BigDecimal("120"), null);
        order.setFamilyId(101L);
        order.setElderId(201L);
        order.setCompanionId(301L);
        order.setHospital("省人民医院");
        order.setDepartment("心内科");
        order.setVisitTime(LocalDateTime.of(2026, 9, 15, 9, 0));
        order.setFinishTime(LocalDateTime.of(2026, 9, 15, 12, 30));

        given(orderMapper.selectList(any())).willReturn(List.of(order));
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of(elder(201L, "李四")));
        given(userNameResolver.resolveAll(any())).willReturn(Map.of(101L, "张三", 301L, "王五"));

        ArgumentCaptor<List<SettlementExportVO>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        service.export(new AdminSettlementQuery(), mock(HttpServletResponse.class));

        verify(exportService).writeExcel(any(), eq("结算单"), eq(SettlementExportVO.class),
                rowsCaptor.capture(), eq("结算单"));
        SettlementExportVO row = rowsCaptor.getValue().get(0);
        assertEquals("120.00", row.getSettleAmount());
        assertEquals("120.00", row.getFee());
        assertEquals("张*", row.getFamilyName());
        assertEquals("李*", row.getElderName());
        assertEquals("王*", row.getCompanionName());
        assertEquals("2026-09-15 09:00:00", row.getVisitTime());
        assertEquals("未结算", row.getPaymentStatusLabel());
    }

    @Test
    @DisplayName("列表：姓名脱敏、结算金额由服务端计算")
    void listShouldMaskNamesAndComputeAmount() {
        CompanionOrder order = order(ORDER_ID, OrderStatus.COMPLETED.name(),
                PaymentStatus.UNPAID.name(), new BigDecimal("120.00"), new BigDecimal("99.00"));
        order.setFamilyId(101L);
        order.setElderId(201L);

        given(orderMapper.selectPage(any(), any())).willReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<CompanionOrder>()
                .setRecords(List.of(order)).setTotal(1).setCurrent(1).setSize(10));
        given(orderReadMapper.selectEldersIgnoringLogicDelete(any())).willReturn(List.of(elder(201L, "李四")));
        given(userNameResolver.resolveAll(any())).willReturn(Map.of(101L, "张三"));

        List<SettlementOrderVO> records = service.list(new AdminSettlementQuery()).getRecords();

        assertEquals(1, records.size());
        assertEquals("99.00", records.get(0).getSettleAmount(), "已有 actual_fee 时应取 actual_fee");
        assertEquals("张*", records.get(0).getFamilyName());
        assertEquals("李*", records.get(0).getElderName());
    }

    /* ================================================================== */
    /* 辅助                                                                */
    /* ================================================================== */

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<LambdaUpdateWrapper<CompanionOrder>> wrapperCaptor() {
        return ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    }

    private static SettlementBatchConfirmDTO batchDto(Long... ids) {
        SettlementBatchConfirmDTO dto = new SettlementBatchConfirmDTO();
        dto.setOrderIds(java.util.Arrays.asList(ids));
        return dto;
    }

    private static CompanionOrder order(Long id, String status, String paymentStatus,
                                        BigDecimal fee, BigDecimal actualFee) {
        CompanionOrder order = new CompanionOrder();
        order.setId(id);
        order.setOrderNo(ORDER_NO);
        order.setStatus(status);
        order.setPaymentStatus(paymentStatus);
        order.setFee(fee);
        order.setActualFee(actualFee);
        return order;
    }

    private static ElderProfile elder(Long id, String name) {
        ElderProfile elder = new ElderProfile();
        elder.setId(id);
        elder.setName(name);
        return elder;
    }
}
