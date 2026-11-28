package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.constant.FulfillmentEventType;
import org.company.nianglin.constant.FulfillmentRisk;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.constant.TriggerSource;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.OrderFulfillmentEvent;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.OrderFulfillmentEventMapper;
import org.company.nianglin.service.MessageService;
import org.company.nianglin.service.OrderTimeoutService;
import org.company.nianglin.service.support.OrderTransitionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单超时闭环实现（closure-2026-11 · P1-1）。
 *
 * <h3>为什么默认不自动取消</h3>
 *
 * <p>自动取消会让「家属其实还想等服务」的订单凭空消失，
 * 代价高于「多挂一会儿」。所以默认只做标记（风险升级 + 通知），
 * 自动取消挂在独立开关 {@code nianglin.order.pending-auto-cancel}（默认 false）上，
 * 上线后看数据再决定要不要打开。</p>
 *
 * <h3>幂等靠「条件更新认领」</h3>
 *
 * <p>每条处理都先做一次带状态与风险条件的 UPDATE：影响行 = 0 说明这条刚被
 * 另一个实例处理过（或订单已被接单 / 取消），于是<b>不写事件、不发通知</b> ——
 * 否则家属会在同一分钟收到重复告警。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderTimeoutServiceImpl implements OrderTimeoutService {

    /** 待接单超时的默认阈值（小时） */
    private static final int DEFAULT_PENDING_TIMEOUT_HOURS = 2;

    /** 服务中超时的默认阈值（小时） */
    private static final int DEFAULT_IN_SERVICE_TIMEOUT_HOURS = 12;

    /** 单轮批次上限的默认值 */
    private static final int DEFAULT_SCAN_BATCH = 200;

    /** 自动取消时写进取消原因与状态日志的文案 */
    private static final String AUTO_CANCEL_REASON = "就诊时间已过且无人接单，系统自动取消";

    private final CompanionOrderMapper orderMapper;
    private final OrderFulfillmentEventMapper eventMapper;
    private final MessageService messageService;
    private final OrderTransitionService orderTransitionService;

    /**
     * 待接单超时阈值（小时）。
     *
     * <p>用 {@link Integer} 而不是 {@code int}：纯 Mockito 单测不起容器，字段保持 {@code null}，
     * 而 {@code null} 必须被当成默认阈值 —— 否则同一套判定会随「有没有 Spring 容器」而变。</p>
     */
    @Value("${nianglin.order.pending-timeout-hours:2}")
    private Integer pendingTimeoutHours;

    /** 服务中超时阈值（小时） */
    @Value("${nianglin.order.in-service-timeout-hours:12}")
    private Integer inServiceTimeoutHours;

    /**
     * 待接单超时后是否自动取消（默认 false）。
     *
     * <p>{@code Boolean} 同理：{@code null}（未注入）按 {@code false} 处理，
     * 也就是「只标记不取消」这个保守档。</p>
     */
    @Value("${nianglin.order.pending-auto-cancel:false}")
    private Boolean pendingAutoCancel;

    /** 单轮候选集上限 */
    @Value("${nianglin.order.timeout-scan-batch:200}")
    private Integer scanBatch;

    @Override
    public int scanTimeouts() {
        LocalDateTime now = LocalDateTime.now();
        int pending = scanPendingTimeouts(now);
        int inService = scanInServiceTimeouts(now);
        int handled = pending + inService;
        if (handled > 0) {
            log.info("订单超时扫描完成 | 待接单={} 条 | 服务中={} 条 | 阈值 待接单={}/服务中={} 小时",
                    pending, inService, pendingTimeoutHours(), inServiceTimeoutHours());
        }
        return handled;
    }

    /* ================================================================== */
    /* A · 待接单超时                                                      */
    /* ================================================================== */

    private int scanPendingTimeouts(LocalDateTime now) {
        LocalDateTime deadline = now.minusHours(pendingTimeoutHours());
        List<CompanionOrder> candidates = orderMapper.selectList(
                Wrappers.<CompanionOrder>lambdaQuery()
                        .eq(CompanionOrder::getStatus, OrderStatus.PENDING.name())
                        // 就诊时间过去超过阈值仍无人接单
                        .lt(CompanionOrder::getVisitTime, deadline)
                        .orderByAsc(CompanionOrder::getVisitTime)
                        .last("LIMIT " + batch()));
        int handled = 0;
        for (CompanionOrder order : cap(candidates)) {
            try {
                if (handlePendingTimeout(order, now)) {
                    handled++;
                }
            } catch (Exception e) {
                // 一条订单处理失败不能拖垮整轮：后面的订单还在等着
                log.warn("待接单超时处理失败 | orderId={} | {}", order.getId(), e.getMessage());
            }
        }
        return handled;
    }

    private boolean handlePendingTimeout(CompanionOrder order, LocalDateTime now) {
        if (Boolean.TRUE.equals(pendingAutoCancel)) {
            return autoCancelExpiredOrder(order, now);
        }
        return markPendingTimeout(order, now);
    }

    /**
     * 自动取消过期未接单订单。
     *
     * <p>状态变更走状态机唯一入口（{@link OrderTransitionService#forceTerminalAsSystem}），
     * 操作人记 SYSTEM —— 定时任务不是「某个管理员点的」，
     * 把系统操作记成管理员操作，审计就失去意义了。</p>
     */
    private boolean autoCancelExpiredOrder(CompanionOrder order, LocalDateTime now) {
        order.setCancelTime(now);
        order.setCancelReason(AUTO_CANCEL_REASON);
        orderTransitionService.forceTerminalAsSystem(order, OrderStatus.CANCELLED, AUTO_CANCEL_REASON);

        FulfillmentRisk risk = riskOf(order);
        writeEvent(order, FulfillmentEventType.ORDER_TIMEOUT, risk, risk, AUTO_CANCEL_REASON);
        notifyFamily(order, "CANCELLED", null);
        return true;
    }

    /**
     * 只标记，不改状态：风险升到「需要人工介入」，让管理员在风险中心看到。
     *
     * <p>条件更新即「认领」：要求库里此刻仍是「PENDING + 旧风险档」，
     * 影响行 = 0 说明已被别处处理过，直接跳过（不写事件、不发通知）。</p>
     */
    private boolean markPendingTimeout(CompanionOrder order, LocalDateTime now) {
        FulfillmentRisk current = riskOf(order);
        if (current.atLeast(FulfillmentRisk.MANUAL_REQUIRED)) {
            return false;
        }
        int rows = orderMapper.update(null, Wrappers.<CompanionOrder>lambdaUpdate()
                .eq(CompanionOrder::getId, order.getId())
                .eq(CompanionOrder::getStatus, OrderStatus.PENDING.name())
                .eq(CompanionOrder::getFulfillmentRisk, current.name())
                .set(CompanionOrder::getFulfillmentRisk, FulfillmentRisk.MANUAL_REQUIRED.name()));
        if (rows == 0) {
            return false;
        }
        writeEvent(order, FulfillmentEventType.ORDER_TIMEOUT, current, FulfillmentRisk.MANUAL_REQUIRED,
                "已过就诊时间 " + pendingTimeoutHours() + " 小时仍无人接单，标记待人工处理");
        notifyFamily(order, "MARKED", null);
        return true;
    }

    /* ================================================================== */
    /* B · 服务中超时                                                      */
    /* ================================================================== */

    private int scanInServiceTimeouts(LocalDateTime now) {
        LocalDateTime deadline = now.minusHours(inServiceTimeoutHours());
        List<CompanionOrder> candidates = orderMapper.selectList(
                Wrappers.<CompanionOrder>lambdaQuery()
                        .eq(CompanionOrder::getStatus, OrderStatus.IN_SERVICE.name())
                        // start_time 为 NULL 的订单（未真正开始服务）不匹配 —— SQL 的 NULL 语义
                        .lt(CompanionOrder::getStartTime, deadline)
                        .orderByAsc(CompanionOrder::getStartTime)
                        .last("LIMIT " + batch()));
        int handled = 0;
        for (CompanionOrder order : cap(candidates)) {
            try {
                if (handleInServiceTimeout(order, now)) {
                    handled++;
                }
            } catch (Exception e) {
                log.warn("服务中超时处理失败 | orderId={} | {}", order.getId(), e.getMessage());
            }
        }
        return handled;
    }

    /**
     * 服务中超时：只升级风险，<b>不改订单状态</b>。
     *
     * <p>不改状态是刻意的：医院排队、路上堵车都可能让服务超过阈值，
     * 系统无权替人判断「服务已经结束」。订单交给管理员与家属去核实。</p>
     */
    private boolean handleInServiceTimeout(CompanionOrder order, LocalDateTime now) {
        FulfillmentRisk current = riskOf(order);
        FulfillmentRisk target = FulfillmentRisk.URGENT;
        // 只升不降：已经是 URGENT 或更高（MANUAL_REQUIRED）就跳过
        if (current.atLeast(target)) {
            return false;
        }
        int rows = orderMapper.update(null, Wrappers.<CompanionOrder>lambdaUpdate()
                .eq(CompanionOrder::getId, order.getId())
                .eq(CompanionOrder::getStatus, OrderStatus.IN_SERVICE.name())
                .eq(CompanionOrder::getFulfillmentRisk, current.name())
                .set(CompanionOrder::getFulfillmentRisk, target.name()));
        if (rows == 0) {
            return false;
        }
        writeEvent(order, FulfillmentEventType.ORDER_IN_SERVICE_TIMEOUT, current, target,
                "进入服务中已超过 " + inServiceTimeoutHours() + " 小时仍未完成，升级为需人工核实");
        notifyFamily(order, "IN_SERVICE", inServiceTimeoutHours());
        return true;
    }

    /* ================================================================== */
    /* 内部                                                                */
    /* ================================================================== */

    private void writeEvent(CompanionOrder order, FulfillmentEventType type,
                            FulfillmentRisk from, FulfillmentRisk to, String remark) {
        OrderFulfillmentEvent event = new OrderFulfillmentEvent();
        event.setOrderId(order.getId());
        event.setEventType(type.name());
        event.setFromRisk(from == null ? null : from.name());
        event.setToRisk(to == null ? null : to.name());
        event.setTriggerSource(TriggerSource.SYSTEM.name());
        // 系统自动触发：没有操作人
        event.setOperatorId(null);
        event.setOperatorRole(RoleConstants.SYSTEM);
        event.setRemark(remark);
        event.setOperateTime(LocalDateTime.now());
        eventMapper.insert(event);
    }

    /** 通知下单家属；文案里不含诊断、不含金额建议（合规红线-1） */
    private void notifyFamily(CompanionOrder order, String state, Integer hours) {
        Map<String, Object> params = new HashMap<>(4);
        params.put("orderNo", order.getOrderNo());
        params.put("state", state);
        if (hours != null) {
            params.put("hours", hours);
        }
        messageService.send(order.getFamilyId(), MessageType.ORDER_TIMEOUT, order.getId(), params);
    }

    /** 库里为 NULL 视为 NORMAL（与履约风险扫描同口径） */
    private FulfillmentRisk riskOf(CompanionOrder order) {
        FulfillmentRisk risk = FulfillmentRisk.of(order.getFulfillmentRisk());
        return risk == null ? FulfillmentRisk.NORMAL : risk;
    }

    /**
     * 单轮处理量上限。
     *
     * <p>SQL 里已经带了 {@code LIMIT}，这里再钳一次：
     * 「单轮处理量受上限约束」是运维要能依赖的契约，
     * 只靠 SQL 的 LIMIT 就没有任何单元测试能证明它。</p>
     */
    private List<CompanionOrder> cap(List<CompanionOrder> candidates) {
        int max = batch();
        return candidates.size() <= max ? candidates : candidates.subList(0, max);
    }

    private int pendingTimeoutHours() {
        return pendingTimeoutHours == null ? DEFAULT_PENDING_TIMEOUT_HOURS : pendingTimeoutHours;
    }

    private int inServiceTimeoutHours() {
        return inServiceTimeoutHours == null
                ? DEFAULT_IN_SERVICE_TIMEOUT_HOURS : inServiceTimeoutHours;
    }

    private int batch() {
        return scanBatch == null ? DEFAULT_SCAN_BATCH : Math.max(1, scanBatch);
    }
}
