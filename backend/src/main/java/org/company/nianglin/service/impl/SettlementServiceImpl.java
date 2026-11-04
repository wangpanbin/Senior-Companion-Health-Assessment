package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.OperTargetType;
import org.company.nianglin.constant.OperType;
import org.company.nianglin.constant.OperationParam;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.PaymentStatus;
import org.company.nianglin.dto.AdminSettlementQuery;
import org.company.nianglin.dto.SettlementBatchConfirmDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.OrderReadMapper;
import org.company.nianglin.service.ExportService;
import org.company.nianglin.service.OperationParamService;
import org.company.nianglin.service.SettlementService;
import org.company.nianglin.service.support.OperLogRecorder;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.util.MaskUtil;
import org.company.nianglin.vo.SettlementBatchConfirmVO;
import org.company.nianglin.vo.SettlementBatchItemVO;
import org.company.nianglin.vo.SettlementConfirmVO;
import org.company.nianglin.vo.SettlementExportVO;
import org.company.nianglin.vo.SettlementOrderVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 结算核对实现（V4 · P2-1）。
 *
 * <h3>为什么用「条件更新 + 回查」而不是先查后改</h3>
 *
 * <p>两名管理员同时点核定时，先查后改会让两人都读到 {@code UNPAID}、
 * 都执行更新、都写一条日志 —— 账面上只结算了一次，日志里却出现两次核定。
 * 条件更新把判断和写入压进同一条 SQL（{@code WHERE payment_status='UNPAID'}），
 * 数据库保证只有一个事务把行数改到 1；另一个人拿到 0 行，回查后得到
 * 「已结算」，返回 {@code settledNow=false}，不写日志。</p>
 *
 * <h3>为什么批量方法自己开事务</h3>
 *
 * <p>批量要满足两个看似矛盾的要求：业务失败逐项返回、系统错误整批回滚。
 * 把 {@code @Transactional} 放在批量方法上、逐项 {@code try/catch BusinessException}，
 * 业务异常在本方法内被消化、不会把事务标记为 rollback-only；
 * 真正的数据库异常不被捕获，直接上抛并回滚整批。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-1
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementServiceImpl implements SettlementService {

    /** 导出与页面统一的时间展示格式 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 金额兜底：DDL 给 {@code fee} 默认 0.00，历史数据为空时也不让页面出现 null */
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    /** 可结算的订单状态：服务已完成（含已完成且已评价） */
    private static final List<String> SETTLEABLE_STATUSES = List.of(
            OrderStatus.COMPLETED.name(), OrderStatus.REVIEWED.name());

    private final CompanionOrderMapper companionOrderMapper;
    private final OrderReadMapper orderReadMapper;
    private final UserNameResolver userNameResolver;
    private final OperLogRecorder operLogRecorder;
    private final ExportService exportService;

    /**
     * 运营参数服务（V4 · P2-3）；纯单测未注入时为 {@code null}，
     * 回落下面的 {@code @Value} 默认值。
     */
    @Autowired(required = false)
    private OperationParamService operationParamService;

    /** 导出单次行数上限（字典缺失 / 读取失败时的默认值），与订单 / 用户导出共用 */
    @Value("${nianglin.export.max-rows:10000}")
    private int maxExportRows;

    /* ================================================================== */
    /* 列表                                                               */
    /* ================================================================== */

    @Override
    public PageResult<SettlementOrderVO> list(AdminSettlementQuery query) {
        Page<CompanionOrder> page = companionOrderMapper.selectPage(query.toMpPage(), listWrapper(query));
        return PageResult.of(page, toOrderVos(page.getRecords()));
    }

    /* ================================================================== */
    /* 单条核定                                                           */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SettlementConfirmVO confirm(Long orderId) {
        CompanionOrder order = companionOrderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }

        // 已结算直接返回：幂等，且不重复留痕
        if (PaymentStatus.SETTLED.name().equals(order.getPaymentStatus())) {
            return toConfirmVo(order, false);
        }
        if (!SETTLEABLE_STATUSES.contains(order.getStatus())) {
            throw new BusinessException(ResultCode.SETTLEMENT_STATUS_ILLEGAL);
        }

        int updated = companionOrderMapper.update(null, Wrappers.<CompanionOrder>lambdaUpdate()
                .eq(CompanionOrder::getId, orderId)
                .eq(CompanionOrder::getPaymentStatus, PaymentStatus.UNPAID.name())
                .in(CompanionOrder::getStatus, SETTLEABLE_STATUSES)
                .set(CompanionOrder::getPaymentStatus, PaymentStatus.SETTLED.name())
                // 金额快照：已有 actual_fee 时保留人工回填值，否则取服务费
                .setSql("actual_fee = COALESCE(actual_fee, fee)"));

        if (updated == 0) {
            // 竞争失败或状态已变：回查后给出准确结论，而不是笼统报错
            CompanionOrder latest = companionOrderMapper.selectById(orderId);
            if (latest == null) {
                throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
            }
            if (PaymentStatus.SETTLED.name().equals(latest.getPaymentStatus())) {
                return toConfirmVo(latest, false);
            }
            throw new BusinessException(ResultCode.SETTLEMENT_STATUS_ILLEGAL);
        }

        CompanionOrder latest = companionOrderMapper.selectById(orderId);
        CompanionOrder settled = latest == null ? order : latest;
        String amount = money(settleAmountOf(settled));

        operLogRecorder.record(OperType.SETTLE_ORDER, OperTargetType.ORDER, orderId, order.getOrderNo(),
                PaymentStatus.UNPAID.name(), PaymentStatus.SETTLED.name(),
                "结算核定：服务费 " + money(order.getFee()) + " 元，结算金额 " + amount + " 元");

        log.info("订单结算核定完成 | orderId={} | orderNo={} | 结算金额={}",
                orderId, order.getOrderNo(), amount);
        return toConfirmVo(settled, true);
    }

    /* ================================================================== */
    /* 批量核定                                                           */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SettlementBatchConfirmVO batchConfirm(SettlementBatchConfirmDTO dto) {
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (Long id : dto.getOrderIds()) {
            if (id != null) {
                uniqueIds.add(id);
            }
        }
        if (uniqueIds.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请选择至少一笔订单");
        }
        // 控制器上的 @Size 是第一道；这里再收一次，避免将来有别的调用方绕过校验
        if (uniqueIds.size() > 100) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "单次最多核定 100 笔订单");
        }

        List<SettlementBatchItemVO> items = new ArrayList<>(uniqueIds.size());
        int settledCount = 0;
        int alreadySettledCount = 0;
        int failedCount = 0;

        for (Long id : uniqueIds) {
            try {
                SettlementConfirmVO result = confirm(id);
                if (Boolean.TRUE.equals(result.getSettledNow())) {
                    settledCount++;
                } else {
                    alreadySettledCount++;
                }
                items.add(new SettlementBatchItemVO()
                        .setOrderId(id)
                        .setOrderNo(result.getOrderNo())
                        .setSuccess(true)
                        .setSettledNow(result.getSettledNow())
                        .setCode(ResultCode.SUCCESS.getCode())
                        .setMessage(Boolean.TRUE.equals(result.getSettledNow()) ? "已核定" : "该单已结算")
                        .setSettleAmount(result.getSettleAmount()));
            } catch (BusinessException e) {
                failedCount++;
                items.add(new SettlementBatchItemVO()
                        .setOrderId(id)
                        .setSuccess(false)
                        .setSettledNow(false)
                        .setCode(e.getCode())
                        .setMessage(e.getMessage()));
            }
        }

        log.info("批量结算核定完成 | 总数={} | 新核定={} | 已结算={} | 失败={}",
                uniqueIds.size(), settledCount, alreadySettledCount, failedCount);
        return new SettlementBatchConfirmVO()
                .setTotal(uniqueIds.size())
                .setSettledCount(settledCount)
                .setAlreadySettledCount(alreadySettledCount)
                .setFailedCount(failedCount)
                .setItems(items);
    }

    /* ================================================================== */
    /* 导出                                                               */
    /* ================================================================== */

    @Override
    public void export(AdminSettlementQuery query, HttpServletResponse response) {
        // 多查一行用于判断是否超限，避免再跑一次 count
        int limit = effectiveMaxExportRows();
        List<CompanionOrder> orders = companionOrderMapper.selectList(
                listWrapper(query).last("LIMIT " + (limit + 1)));
        if (orders.size() > limit) {
            throw new BusinessException(ResultCode.EXPORT_LIMIT_EXCEEDED,
                    "本次筛选结果超过 " + limit + " 行，请缩小筛选范围后再导出");
        }

        List<SettlementOrderVO> vos = toOrderVos(orders);
        List<SettlementExportVO> rows = vos.stream().map(SettlementServiceImpl::toExportRow).toList();
        exportService.writeExcel(response, "结算单", SettlementExportVO.class, rows, "结算单");
    }

    /* ================================================================== */
    /* 内部：查询条件                                                     */
    /* ================================================================== */

    private LambdaQueryWrapper<CompanionOrder> listWrapper(AdminSettlementQuery query) {
        LambdaQueryWrapper<CompanionOrder> wrapper = Wrappers.<CompanionOrder>lambdaQuery()
                // 台账只收服务已完成的订单，取消单永不进入
                .in(CompanionOrder::getStatus, SETTLEABLE_STATUSES);

        if (StringUtils.hasText(query.getPaymentStatus())) {
            PaymentStatus status = PaymentStatus.of(query.getPaymentStatus());
            if (status == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "结算状态只能是 UNPAID 或 SETTLED");
            }
            wrapper.eq(CompanionOrder::getPaymentStatus, status.name());
        }

        if (StringUtils.hasText(query.getKeyword())) {
            String keyword = query.getKeyword().trim();
            wrapper.and(w -> w.like(CompanionOrder::getOrderNo, keyword)
                    .or().like(CompanionOrder::getHospital, keyword)
                    .or().like(CompanionOrder::getDepartment, keyword));
        }

        applyFinishRange(wrapper, query.getStartDate(), query.getEndDate());
        return wrapper.orderByDesc(CompanionOrder::getFinishTime).orderByDesc(CompanionOrder::getId);
    }

    /**
     * 周期按服务完成时间过滤（闭区间，止日含 23:59:59）。
     *
     * <p>与订单统计的「下单时间」不是同一口径 —— 结算依据是服务完成，
     * 用下单时间会把「上月下单、本月完成」的单算进错误周期。</p>
     */
    private void applyFinishRange(LambdaQueryWrapper<CompanionOrder> wrapper, String startDate, String endDate) {
        LocalDate start = parseDate(startDate, "服务完成时间起");
        LocalDate end = parseDate(endDate, "服务完成时间止");
        if (start != null && end != null && start.isAfter(end)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "服务完成时间区间的开始日期不能晚于结束日期");
        }
        if (start != null) {
            wrapper.ge(CompanionOrder::getFinishTime, start.atStartOfDay());
        }
        if (end != null) {
            wrapper.le(CompanionOrder::getFinishTime, end.atTime(23, 59, 59));
        }
    }

    private LocalDate parseDate(String value, String field) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, field + "格式应为 yyyy-MM-dd");
        }
    }

    /* ================================================================== */
    /* 内部：实体 → VO                                                   */
    /* ================================================================== */

    private List<SettlementOrderVO> toOrderVos(List<CompanionOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }

        Set<Long> elderIds = orders.stream()
                .map(CompanionOrder::getElderId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, ElderProfile> elders = elderIds.isEmpty() ? Map.of()
                : orderReadMapper.selectEldersIgnoringLogicDelete(elderIds).stream()
                .collect(Collectors.toMap(ElderProfile::getId, Function.identity(), (a, b) -> a));

        Set<Long> userIds = orders.stream()
                .flatMap(o -> Stream.of(o.getFamilyId(), o.getCompanionId()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, String> userNames = userNameResolver.resolveAll(userIds);

        return orders.stream().map(order -> {
            ElderProfile elder = elders.get(order.getElderId());
            return new SettlementOrderVO()
                    .setId(order.getId())
                    .setOrderNo(order.getOrderNo())
                    .setFamilyName(MaskUtil.name(userNames.get(order.getFamilyId())))
                    .setElderName(MaskUtil.name(elder == null ? null : elder.getName()))
                    .setCompanionName(order.getCompanionId() == null
                            ? null : MaskUtil.name(userNames.get(order.getCompanionId())))
                    .setHospital(order.getHospital())
                    .setDepartment(order.getDepartment())
                    .setVisitTime(formatTime(order.getVisitTime()))
                    .setFinishTime(formatTime(order.getFinishTime()))
                    .setFee(money(order.getFee()))
                    .setSettleAmount(money(settleAmountOf(order)))
                    .setPaymentStatus(order.getPaymentStatus())
                    .setPaymentStatusLabel(PaymentStatus.labelOf(order.getPaymentStatus()))
                    .setStatus(order.getStatus())
                    .setStatusLabel(OrderStatus.labelOf(order.getStatus()));
        }).toList();
    }

    private SettlementConfirmVO toConfirmVo(CompanionOrder order, boolean settledNow) {
        return new SettlementConfirmVO()
                .setOrderId(order.getId())
                .setOrderNo(order.getOrderNo())
                .setPaymentStatus(order.getPaymentStatus())
                .setPaymentStatusLabel(PaymentStatus.labelOf(order.getPaymentStatus()))
                .setSettleAmount(money(settleAmountOf(order)))
                .setSettledNow(settledNow);
    }

    private static SettlementExportVO toExportRow(SettlementOrderVO vo) {
        return new SettlementExportVO()
                .setOrderNo(vo.getOrderNo())
                .setFamilyName(vo.getFamilyName())
                .setElderName(vo.getElderName())
                .setCompanionName(vo.getCompanionName())
                .setHospital(vo.getHospital())
                .setDepartment(vo.getDepartment())
                .setVisitTime(vo.getVisitTime())
                .setFinishTime(vo.getFinishTime())
                .setFee(vo.getFee())
                .setSettleAmount(vo.getSettleAmount())
                .setPaymentStatusLabel(vo.getPaymentStatusLabel())
                .setStatusLabel(vo.getStatusLabel());
    }

    /* ================================================================== */
    /* 内部：金额与时间                                                   */
    /* ================================================================== */

    /** 结算金额：已有 actual_fee 取之，否则取服务费（与统计/导出同一口径） */
    private static BigDecimal settleAmountOf(CompanionOrder order) {
        if (order.getActualFee() != null) {
            return order.getActualFee();
        }
        return order.getFee() == null ? ZERO : order.getFee();
    }

    /** 两位小数字符串：对账场景下不把格式化交给前端或 Excel 默认值 */
    private static String money(BigDecimal value) {
        BigDecimal safe = value == null ? ZERO : value;
        return safe.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FORMAT);
    }

    /** 运营参数优先；纯单测无 Spring 注入时回落 @Value 默认值 */
    private int effectiveMaxExportRows() {
        return operationParamService == null
                ? maxExportRows
                : operationParamService.getInt(OperationParam.EXPORT_MAX_ROWS);
    }
}
