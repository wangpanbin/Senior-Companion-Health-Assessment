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
 * <p>三条硬口径：① 明细是真源，代垫 / 服务费合计与 {@code actual_fee} 都由
 * {@link #totalsOf} 一处算出 —— 有 SERVICE 明细时服务费取明细求和，没有时回落订单申报
 * {@code fee}，完全无明细时保留历史申报值兜底（ADR-0009 修订）；
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
            recalcActualFee(order);
        }
        return OrderFeeItemVO.of(item);
    }

    @Override
    @Transactional(readOnly = true)
    public OrderFeeItemSummaryVO listByOrder(Long orderId) {
        CompanionOrder order = requireOrder(orderId);
        checkReadPermission(order);

        List<OrderFeeItem> items = itemsOf(orderId);

        Totals totals = totalsOf(order, items);
        OrderFeeItemSummaryVO vo = new OrderFeeItemSummaryVO();
        vo.setItems(items.stream().map(OrderFeeItemVO::of).toList());
        vo.setAdvanceTotal(money(totals.advance()));
        vo.setServiceTotal(money(totals.service()));
        vo.setTotal(money(totals.total()));
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
        CompanionOrder order = requireOrder(orderId);
        List<OrderFeeItem> items = itemsOf(orderId);
        // dto.fee 申报的是服务费；已有 SERVICE 明细则不重复生成。
        // 判据是「是否存在 SERVICE 明细」而非「SERVICE 求和是否为 0」：
        // 0.00 的服务费明细合法，按求和判会重复插入第二行
        if (declaredFee != null && !hasType(items, TYPE_SERVICE)) {
            OrderFeeItem service = new OrderFeeItem();
            service.setOrderId(orderId);
            service.setItemType(TYPE_SERVICE);
            service.setItemName("陪诊服务费");
            service.setAmount(declaredFee);
            service.setOccurredAt(LocalDateTime.now());
            feeItemMapper.insert(service);
            items.add(service);
        }
        if (items.isEmpty()) {
            // 未申报服务费且一条明细都没有：不写 0，保留历史值（ADR-0009 兜底口径）
            return;
        }
        // actual_fee 与读路径共用 totalsOf，否则收入页「账单行」与「展开面板」会给出两个总价
        CompanionOrder patch = new CompanionOrder();
        patch.setId(orderId);
        patch.setActualFee(totalsOf(order, items).total());
        orderMapper.updateById(patch);
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

    /**
     * 重算汇总缓存：{@code actual_fee} = 代垫 + 服务费，与读路径共用 {@link #totalsOf}。
     * 完全无明细时不动 —— 历史订单的申报值兜底口径（ADR-0009）。
     */
    private void recalcActualFee(CompanionOrder order) {
        List<OrderFeeItem> items = itemsOf(order.getId());
        if (items.isEmpty()) {
            return;
        }
        CompanionOrder patch = new CompanionOrder();
        patch.setId(order.getId());
        patch.setActualFee(totalsOf(order, items).total());
        orderMapper.updateById(patch);
    }

    /** 本单全部明细，按发生时间升序 —— 读路径与重算共用，保证两处面对同一份数据 */
    private List<OrderFeeItem> itemsOf(Long orderId) {
        return feeItemMapper.selectList(Wrappers.<OrderFeeItem>lambdaQuery()
                .eq(OrderFeeItem::getOrderId, orderId)
                .orderByAsc(OrderFeeItem::getOccurredAt)
                .orderByAsc(OrderFeeItem::getId));
    }

    private static boolean hasType(List<OrderFeeItem> items, String itemType) {
        return items.stream().anyMatch(item -> itemType.equals(item.getItemType()));
    }

    /**
     * 费用汇总口径的**唯一实现** —— 读路径（{@link #listByOrder}）与 {@code actual_fee}
     * 重算（{@link #recalcActualFee} / {@link #applyOnComplete}）必须共用它，否则同一张单
     * 在收入页会给出两个总价（账单行取 {@code actual_fee}，展开面板取 {@code total}）。
     *
     * <p>服务费判据是「**是否存在** SERVICE 明细」而不是「SERVICE 求和是否为 0」：
     * {@code amount = "0.00"} 的 SERVICE 明细合法（{@code FeeItemCreateDTO} 的金额正则允许），
     * 按求和判会把已录入的 0 元明细当成没录，转而报订单申报值。</p>
     */
    private static Totals totalsOf(CompanionOrder order, List<OrderFeeItem> items) {
        BigDecimal advance = BigDecimal.ZERO;
        BigDecimal service = BigDecimal.ZERO;
        boolean hasServiceItem = false;
        for (OrderFeeItem item : items) {
            if (TYPE_ADVANCE.equals(item.getItemType())) {
                advance = advance.add(item.getAmount());
            } else if (TYPE_SERVICE.equals(item.getItemType())) {
                hasServiceItem = true;
                service = service.add(item.getAmount());
            }
        }
        // v1.1.0 口径：还没有 SERVICE 明细时服务费回落订单申报服务费，
        // 保证汇总区与订单详情「服务费」展示一致
        if (!hasServiceItem && order.getFee() != null) {
            service = order.getFee();
        }
        return new Totals(advance, service);
    }

    /** 费用分组合计（代垫 / 服务费）与总合计 */
    private record Totals(BigDecimal advance, BigDecimal service) {

        /** 总合计 —— {@code actual_fee} 与汇总响应里的 {@code total} 取的就是这个值 */
        BigDecimal total() {
            return advance.add(service);
        }
    }

    private String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
