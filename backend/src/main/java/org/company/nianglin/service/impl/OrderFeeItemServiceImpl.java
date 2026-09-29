package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.FeeItemCreateDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.OrderFeeItem;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.ElderProfileMapper;
import org.company.nianglin.mapper.OrderFeeItemMapper;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.OrderFeeItemService;
import org.company.nianglin.vo.OrderFeeItemSummaryVO;
import org.company.nianglin.vo.OrderFeeItemVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 费用明细实现（ADR-0009）。
 *
 * <p>三条硬口径：① 明细是真源，actual_fee 只由明细 SUM 派生（无明细时保留历史申报值兜底）；
 * ② 录入限本单陪诊员，且订单须 IN_SERVICE / COMPLETED；③ 查询限相关方
 * （下单家属 / 就诊老人 / 本单陪诊员 / ADMIN）。</p>
 *
 * @author 银龄伴诊团队
 * @since M4（收敛迭代 T2.5）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderFeeItemServiceImpl implements OrderFeeItemService {

    private static final String TYPE_ADVANCE = "ADVANCE";
    private static final String TYPE_SERVICE = "SERVICE";
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999.99");

    private final CompanionOrderMapper orderMapper;
    private final OrderFeeItemMapper feeItemMapper;
    private final ElderProfileMapper elderProfileMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderFeeItemVO create(Long orderId, FeeItemCreateDTO dto) {
        CompanionOrder order = requireOrder(orderId);
        String status = order.getStatus();
        if (!OrderStatus.IN_SERVICE.name().equals(status) && !OrderStatus.COMPLETED.name().equals(status)) {
            throw new BusinessException(ResultCode.ORDER_STATUS_ILLEGAL);
        }
        if (!Objects.equals(order.getCompanionId(), SecurityUtils.currentUserId())) {
            throw new BusinessException(ResultCode.NOT_ORDER_COMPANION);
        }
        BigDecimal amount = new BigDecimal(dto.getAmount());
        if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "金额须在 0.01 ~ 999999.99 之间");
        }

        OrderFeeItem item = new OrderFeeItem();
        item.setOrderId(orderId);
        item.setItemType(dto.getItemType());
        item.setItemName(dto.getItemName());
        item.setAmount(amount);
        item.setOccurredAt(dto.getOccurredAt());
        feeItemMapper.insert(item);
        log.info("费用明细录入 | orderId={} | type={} | amount={}", orderId, dto.getItemType(),
                amount.setScale(2, RoundingMode.HALF_UP).toPlainString());

        if (OrderStatus.COMPLETED.name().equals(status)) {
            recalcActualFee(orderId);
        }
        return OrderFeeItemVO.of(item);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderFeeItemSummaryVO listByOrder(Long orderId) {
        CompanionOrder order = requireOrder(orderId);
        checkReadPermission(order);

        List<OrderFeeItem> items = feeItemMapper.selectList(Wrappers.<OrderFeeItem>lambdaQuery()
                .eq(OrderFeeItem::getOrderId, orderId)
                .orderByAsc(OrderFeeItem::getOccurredAt)
                .orderByAsc(OrderFeeItem::getId));

        OrderFeeItemSummaryVO vo = new OrderFeeItemSummaryVO();
        vo.setItems(items.stream().map(OrderFeeItemVO::of).toList());
        BigDecimal advance = sumOf(orderId, TYPE_ADVANCE);
        BigDecimal service = sumOf(orderId, TYPE_SERVICE);
        if (service.compareTo(BigDecimal.ZERO) == 0 && order.getFee() != null) {
            // 明细里还没有 SERVICE 项时，服务费合计回落到订单申报服务费：
            // 汇总区与「订单信息」卡的服务费保持同一口径（E2E 审计 P2-1）。
            // 与 ADR-0009 的 actual_fee 兜底同源 —— 有 SERVICE 明细时明细是真源，不回落
            service = order.getFee();
        }
        vo.setAdvanceTotal(money(advance));
        vo.setServiceTotal(money(service));
        vo.setTotal(money(advance.add(service)));
        vo.setHasItems(!items.isEmpty());
        // ADR-0009 兜底：历史订单允许明细为空且仅 actual_fee 有值 —— 显式提示，不伪造明细
        if (items.isEmpty() && order.getActualFee() != null) {
            vo.setFallbackNotice("该订单无明细记录");
        }
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyOnComplete(Long orderId, BigDecimal declaredFee) {
        // dto.fee 申报的是服务费；服务中已记过 SERVICE 明细则不重复生成
        if (declaredFee != null && sumOf(orderId, TYPE_SERVICE).compareTo(BigDecimal.ZERO) == 0) {
            OrderFeeItem service = new OrderFeeItem();
            service.setOrderId(orderId);
            service.setItemType(TYPE_SERVICE);
            service.setItemName("陪诊服务费");
            service.setAmount(declaredFee);
            service.setOccurredAt(LocalDateTime.now());
            feeItemMapper.insert(service);
        }
        BigDecimal sum = sumOf(orderId, null);
        BigDecimal actualFee = sum.compareTo(BigDecimal.ZERO) > 0 ? sum : declaredFee;
        if (actualFee != null) {
            CompanionOrder patch = new CompanionOrder();
            patch.setId(orderId);
            patch.setActualFee(actualFee);
            orderMapper.updateById(patch);
        }
    }

    /* ==================== 内部工具 ==================== */

    private CompanionOrder requireOrder(Long orderId) {
        CompanionOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        return order;
    }

    /** 相关方：下单家属 / 就诊老人本人 / 本单陪诊员 / 管理员，其余 3004 */
    private void checkReadPermission(CompanionOrder order) {
        Long me = SecurityUtils.currentUserId();
        if (RoleConstants.ADMIN.equals(SecurityUtils.currentRole())) {
            return;
        }
        boolean related = Objects.equals(order.getFamilyId(), me)
                || Objects.equals(order.getCompanionId(), me);
        if (!related) {
            ElderProfile elder = elderProfileMapper.selectById(order.getElderId());
            related = elder != null && Objects.equals(elder.getUserId(), me);
        }
        if (!related) {
            throw new BusinessException(ResultCode.ORDER_NO_PERMISSION);
        }
    }

    /** 重算汇总缓存：actual_fee = SUM(明细)。无明细不动（历史兜底口径） */
    private void recalcActualFee(Long orderId) {
        BigDecimal sum = sumOf(orderId, null);
        if (sum.compareTo(BigDecimal.ZERO) > 0) {
            CompanionOrder patch = new CompanionOrder();
            patch.setId(orderId);
            patch.setActualFee(sum);
            orderMapper.updateById(patch);
        }
    }

    private BigDecimal sumOf(Long orderId, String itemType) {
        List<OrderFeeItem> items = feeItemMapper.selectList(Wrappers.<OrderFeeItem>lambdaQuery()
                .eq(OrderFeeItem::getOrderId, orderId)
                .eq(itemType != null, OrderFeeItem::getItemType, itemType));
        return items.stream().map(OrderFeeItem::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
