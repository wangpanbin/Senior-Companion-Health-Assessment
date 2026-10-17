package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.FulfillmentProperties;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.constant.FulfillmentEventType;
import org.company.nianglin.constant.FulfillmentRisk;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.constant.TriggerSource;
import org.company.nianglin.constant.WorkStatus;
import org.company.nianglin.dto.OrderUrgeDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.OrderFulfillmentEvent;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.OrderFulfillmentEventMapper;
import org.company.nianglin.service.FulfillmentService;
import org.company.nianglin.service.MessageService;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.support.FulfillmentRiskEvaluator;
import org.company.nianglin.util.ComplianceCheckUtil;
import org.company.nianglin.util.TimeUtil;
import org.company.nianglin.vo.OrderFulfillmentEventVO;
import org.company.nianglin.vo.OrderFulfillmentVO;
import org.company.nianglin.vo.OrderUrgeResultVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 履约保障服务实现（V3.1）。
 *
 * <h3>三条不可动摇的不变量</h3>
 * <ol>
 *   <li><b>不改订单状态。</b>风险升级只写 {@code fulfillment_risk}，
 *       状态机一行都不碰（ADR-0010）。</li>
 *   <li><b>只升不降。</b>扫描路径不存在把等级写低的代码路径。</li>
 *   <li><b>一次扫描一条事件。</b>订单从 NORMAL 直接连升到 MANUAL_REQUIRED 时，
 *       只写一条事件（from=读到的旧值，to=最终等级），只发一条通知。
 *       否则家属会在同一分钟收到「预警」「紧急」「需人工」三条递进消息 ——
 *       那不是三次风险，是一次风险被说成了三遍。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FulfillmentServiceImpl implements FulfillmentService {

    private final CompanionOrderMapper orderMapper;
    private final OrderFulfillmentEventMapper eventMapper;
    private final CompanionProfileMapper companionProfileMapper;
    private final MessageService messageService;
    private final FulfillmentProperties properties;

    /* ================================================================== */
    /* 定时任务入口                                                        */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int scanAndEscalate() {
        LocalDateTime now = LocalDateTime.now();

        // 候选集：已经等到预警线以上的待接单订单。
        // 按下单时间升序 —— 等得最久的先处理，也让批次上限优先覆盖最危险的那批。
        List<CompanionOrder> candidates = orderMapper.selectPage(
                new Page<>(1L, properties.getScanBatch()),
                Wrappers.<CompanionOrder>lambdaQuery()
                        .eq(CompanionOrder::getStatus, OrderStatus.PENDING.name())
                        .le(CompanionOrder::getCreateTime,
                                now.minusMinutes(properties.getWarningMinutes()))
                        .orderByAsc(CompanionOrder::getCreateTime))
                .getRecords();

        if (candidates.isEmpty()) {
            return 0;
        }

        int escalated = 0;
        for (CompanionOrder order : candidates) {
            if (escalateOne(order, now)) {
                escalated++;
            }
        }

        if (escalated > 0) {
            log.info("履约风险扫描完成 | 候选={} 条 | 升级={} 条 | 阈值 预警={}/紧急={}/人工介入={} 分钟",
                    candidates.size(), escalated,
                    properties.getWarningMinutes(), properties.getUrgentMinutes(),
                    properties.getManualRequiredMinutes());
        }
        return escalated;
    }

    /**
     * 尝试把单条订单升级到应有等级。
     *
     * @return 是否真的发生了升级（被抢或无需升级都返回 {@code false}）
     */
    private boolean escalateOne(CompanionOrder order, LocalDateTime now) {
        // 钳制规则与订单出参、履约详情共用 TimeUtil —— 三处若各算各的，
        // 会出现「管理端说等了很久、家属端显示 0 分钟」这种没法自证的分歧
        int waitMinutes = TimeUtil.elapsedMinutes(order.getCreateTime(), now);

        FulfillmentRisk expected = FulfillmentRiskEvaluator.evaluate(
                waitMinutes,
                properties.getWarningMinutes(),
                properties.getUrgentMinutes(),
                properties.getManualRequiredMinutes());

        // 库里为 NULL 视为 NORMAL：V6 之前的订单没有这一列值，
        // 用 null 去和枚举比较会让它们永远被当成「无需升级」。
        FulfillmentRisk actual = FulfillmentRisk.of(order.getFulfillmentRisk());
        if (actual == null) {
            actual = FulfillmentRisk.NORMAL;
        }

        // 只升不降；已经到顶或无需升级直接跳过
        if (!expected.atLeast(actual) || expected == actual) {
            return false;
        }

        // 条件更新即「认领」：要求库里此刻仍是我读到的那一档。
        // rows = 0 说明这一条刚被另一实例处理、或订单已被接单/取消 —— 跳过，
        // 不写事件、不发通知，否则家属会收到重复告警。
        int rows = orderMapper.update(null, Wrappers.<CompanionOrder>lambdaUpdate()
                .eq(CompanionOrder::getId, order.getId())
                .eq(CompanionOrder::getStatus, OrderStatus.PENDING.name())
                .eq(CompanionOrder::getFulfillmentRisk, actual.name())
                .set(CompanionOrder::getFulfillmentRisk, expected.name()));
        if (rows == 0) {
            return false;
        }

        writeEvent(order.getId(), eventTypeOf(expected), actual, expected,
                TriggerSource.SYSTEM, null, RoleConstants.SYSTEM,
                "已等待 " + waitMinutes + " 分钟，系统自动升级履约风险");
        notifyRiskChanged(order, expected);
        return true;
    }

    /* ================================================================== */
    /* 家属催单                                                            */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OrderUrgeResultVO urge(Long orderId, OrderUrgeDTO dto) {
        LoginUser me = SecurityUtils.currentUser();

        CompanionOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BusinessException(ResultCode.ORDER_NOT_FOUND);
        }
        // 先判归属：非下单人根本不该看到「能不能催」这个问题的答案
        if (!Objects.equals(order.getFamilyId(), me.userId())) {
            throw new BusinessException(ResultCode.ORDER_NO_PERMISSION);
        }
        if (!OrderStatus.PENDING.name().equals(order.getStatus())) {
            throw new BusinessException(ResultCode.ORDER_URGE_NOT_ALLOWED);
        }
        FulfillmentRisk current = FulfillmentRisk.of(order.getFulfillmentRisk());
        if (current == null || !current.atLeast(FulfillmentRisk.URGENT)) {
            throw new BusinessException(ResultCode.ORDER_URGE_NOT_ALLOWED);
        }

        String remark = normalizeRemark(dto == null ? null : dto.getRemark());
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cooldownBoundary = now.minusMinutes(properties.getUrgeCooldownMinutes());
        int maxTimes = properties.getUrgeMaxTimes();

        // ⚠️ 这里是整个 T3 的并发核心：自增 + 全部资格判定合并成一条 UPDATE。
        // 先查后写会让两个并发请求都通过校验并把 urge_count 写成同一个值；
        // 影响 0 行 = 被别人抢先 / 在冷却期 / 已超限，三者对外统一报 3009。
        //
        // 自增走 setSql 而不是 set(预读值 + 1)：spec §2.2 要求 `urge_count = urge_count + 1`。
        // 用预读值会让「读到 2 → 写 3」这条路径在并发下把两个请求写成同一个值，
        // 计数少加一次 —— 上限保护随之失真。SQL 侧自增由数据库保证原子性。
        int rows = orderMapper.update(null, Wrappers.<CompanionOrder>lambdaUpdate()
                .eq(CompanionOrder::getId, orderId)
                .eq(CompanionOrder::getStatus, OrderStatus.PENDING.name())
                .in(CompanionOrder::getFulfillmentRisk,
                        FulfillmentRisk.URGENT.name(), FulfillmentRisk.MANUAL_REQUIRED.name())
                .lt(CompanionOrder::getUrgeCount, maxTimes)
                .and(w -> w.isNull(CompanionOrder::getLastUrgeAt)
                        .or().le(CompanionOrder::getLastUrgeAt, cooldownBoundary))
                .setSql("urge_count = urge_count + 1")
                .set(CompanionOrder::getLastUrgeAt, now));
        if (rows == 0) {
            throw new BusinessException(ResultCode.ORDER_URGE_TOO_FREQUENT);
        }

        int newCount = (order.getUrgeCount() == null ? 0 : order.getUrgeCount()) + 1;
        writeEvent(orderId, FulfillmentEventType.USER_URGED, current, current,
                TriggerSource.USER, me.userId(), RoleConstants.FAMILY, remark);
        notifyAvailableCompanions(order);

        LocalDateTime next = newCount >= maxTimes ? null : now.plusMinutes(properties.getUrgeCooldownMinutes());
        log.info("家属已催单 | orderId={} | familyId={} | 累计={}/{}",
                orderId, me.userId(), newCount, maxTimes);
        return OrderUrgeResultVO.of(orderId, newCount, now, next);
    }

    /* ================================================================== */
    /* 只读：履约详情                                                      */
    /* ================================================================== */

    @Override
    public OrderFulfillmentVO fulfillmentDetail(CompanionOrder order) {
        FulfillmentRisk risk = FulfillmentRisk.of(order.getFulfillmentRisk());
        if (risk == null) {
            risk = FulfillmentRisk.NORMAL;
        }
        boolean pending = OrderStatus.PENDING.name().equals(order.getStatus());
        // 与 OrderVO#base 同口径收敛：详情页与订单列表若对同一笔订单给出两种风险，
        // 家属会先怀疑自己看错了，再怀疑平台
        FulfillmentRisk visible = pending ? risk : FulfillmentRisk.NORMAL;
        int urgeCount = order.getUrgeCount() == null ? 0 : order.getUrgeCount();

        List<OrderFulfillmentEventVO> events = eventMapper.selectList(
                        Wrappers.<OrderFulfillmentEvent>lambdaQuery()
                                .eq(OrderFulfillmentEvent::getOrderId, order.getId())
                                .orderByAsc(OrderFulfillmentEvent::getOperateTime)
                                .orderByAsc(OrderFulfillmentEvent::getId))
                .stream()
                // 升序由两处共同保证：SQL 的 ORDER BY 走索引，这份排序兜住「保证属于这个 VO」。
                // 两者同向，不是冗余——将来有人改了查询条件，契约不会跟着失效。
                // operateTime / id 可能为 null（并发同秒写入），null 排最后而不是抛 NPE。
                .sorted(Comparator.comparing(OrderFulfillmentEvent::getOperateTime,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(OrderFulfillmentEvent::getId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(e -> OrderFulfillmentEventVO.of(e.getEventType(), e.getFromRisk(), e.getToRisk(),
                        e.getTriggerSource(), e.getOperatorRole(), e.getRemark(), e.getOperateTime()))
                .toList();

        return OrderFulfillmentVO.of(
                new OrderFulfillmentVO.CompanionOrderSnapshot(
                        order.getId(), order.getOrderNo(), order.getStatus(),
                        OrderStatus.labelOf(order.getStatus()),
                        visible.name(), visible.getLabel(),
                        pending ? waitMinutesOf(order) : 0,
                        urgeCount,
                        pending && visible.atLeast(FulfillmentRisk.URGENT),
                        nextUrgeAvailableAt(order, urgeCount, pending)),
                events);
    }

    /** 下次可催时间：不可催时为 null；从未催过单且可催时就是「现在」 */
    private LocalDateTime nextUrgeAvailableAt(CompanionOrder order, int urgeCount, boolean pending) {
        FulfillmentRisk risk = FulfillmentRisk.of(order.getFulfillmentRisk());
        if (!pending || risk == null || !risk.atLeast(FulfillmentRisk.URGENT)) {
            return null;
        }
        if (urgeCount >= properties.getUrgeMaxTimes()) {
            return null;
        }
        if (order.getLastUrgeAt() == null) {
            return null;
        }
        return order.getLastUrgeAt().plusMinutes(properties.getUrgeCooldownMinutes());
    }

    private static int waitMinutesOf(CompanionOrder order) {
        return TimeUtil.elapsedMinutes(order.getCreateTime(), LocalDateTime.now());
    }

    /* ================================================================== */
    /* 下单登记                                                            */
    /* ================================================================== */

    @Override
    public void registerCreated(Long orderId, Long familyId, String orderNo) {
        writeEvent(orderId, FulfillmentEventType.ORDER_CREATED, null, FulfillmentRisk.NORMAL,
                TriggerSource.SYSTEM, familyId, RoleConstants.FAMILY, "订单已创建，开始匹配陪诊员");
        log.info("履约事件已登记 | orderId={} | 订单号={}", orderId, orderNo);
    }

    /* ================================================================== */
    /* 内部工具                                                            */
    /* ================================================================== */

    /**
     * 风险等级 → 事件类型。
     *
     * <p>{@link FulfillmentRisk#NORMAL} 落到 {@code MANUAL_REQUIRED} 是「退回正常」，
     * 那条路径不该由扫描触发（扫描只升不降），但真发生时也需要一条可追溯的记录，
     * 所以仍返回 {@code MANUAL_REQUIRED} 之外的一个合法值而不是 {@code null}。</p>
     */
    /**
     * 风险等级 → 风险类事件类型。
     *
     * <p>{@code NORMAL} 不属于「风险升高」，理论上不该进来 —— 但扫描只对
     * 「应有等级高于当前等级」的订单动手，而 NORMAL 是最低档，
     * 永远不会高于任何等级，因此这条分支不可达。{@code default} 兜住它，
     * 是为了脏数据（库里存了枚举之外的值、{@code of} 返回 null）不至于 NPE，
     * <b>不是</b>「NORMAL 也要记一条需要人工介入的事件」。</p>
     */
    /**
     * 风险等级 → 风险类事件类型。
     *
     * <p>{@code NORMAL} 不属于「风险升高」，理论上不该进来 —— 但扫描只对
     * 「应有等级高于当前等级」的订单动手，而 NORMAL 是最低档，
     * 永远不会高于任何等级，因此这条分支不可达。{@code default} 兜住它，
     * 是为了脏数据（库里存了枚举之外的值、{@code of} 返回 null）不至于 NPE，
     * <b>不是</b>「NORMAL 也要记一条需要人工介入的事件」。</p>
     */
    /**
     * 风险等级 → 风险类事件类型。
     *
     * <p>{@code NORMAL} 不属于「风险升高」，理论上不该进来 —— 但扫描只对
     * 「应有等级高于当前等级」的订单动手，而 NORMAL 是最低档，
     * 永远不会高于任何等级，因此这条分支不可达。{@code default} 兜住它，
     * 是为了脏数据（库里存了枚举之外的值、{@code of} 返回 null）不至于 NPE，
     * <b>不是</b>「NORMAL 也要记一条需要人工介入的事件」。</p>
     */
    private FulfillmentEventType eventTypeOf(FulfillmentRisk risk) {
        return switch (risk) {
            case WARNING -> FulfillmentEventType.FULFILLMENT_WARNING;
            case URGENT -> FulfillmentEventType.FULFILLMENT_URGENT;
            default -> FulfillmentEventType.MANUAL_REQUIRED;
        };
    }

    private void writeEvent(Long orderId, FulfillmentEventType type,
                            FulfillmentRisk from, FulfillmentRisk to,
                            TriggerSource source, Long operatorId, String operatorRole,
                            String remark) {
        OrderFulfillmentEvent event = new OrderFulfillmentEvent();
        event.setOrderId(orderId);
        event.setEventType(type.name());
        event.setFromRisk(from == null ? null : from.name());
        event.setToRisk(to == null ? null : to.name());
        event.setTriggerSource(source.name());
        event.setOperatorId(operatorId);
        event.setOperatorRole(operatorRole);
        event.setRemark(remark);
        event.setOperateTime(LocalDateTime.now());
        eventMapper.insert(event);
    }

    /** 风险升到需要人看的等级时通知下单家属；MANUAL_REQUIRED 由管理员侧兜底，不打扰家属 */
    private void notifyRiskChanged(CompanionOrder order, FulfillmentRisk risk) {
        MessageType messageType = switch (risk) {
            case WARNING -> MessageType.FULFILLMENT_WARNING;
            case URGENT -> MessageType.FULFILLMENT_URGENT;
            default -> null;
        };
        if (messageType == null) {
            return;
        }
        Map<String, Object> params = new HashMap<>(4);
        params.put("orderNo", order.getOrderNo());
        messageService.send(order.getFamilyId(), messageType, order.getId(), params);
    }

    /**
     * 催单时通知当前可接单的陪诊员。
     *
     * <p>只推「资质已通过 + 处于接单中」的人：推给休息中或离线的人，
     * 既接不了单又被打扰，只会让他更快地关掉接单开关 —— 那与催单的目的正好相反。</p>
     */
    private void notifyAvailableCompanions(CompanionOrder order) {
        List<Long> userIds = companionProfileMapper.selectList(
                        Wrappers.<CompanionProfile>lambdaQuery()
                                .eq(CompanionProfile::getAuditStatus, AuditStatus.APPROVED.name())
                                .eq(CompanionProfile::getWorkStatus, WorkStatus.AVAILABLE.name())
                                .select(CompanionProfile::getUserId))
                .stream()
                .map(CompanionProfile::getUserId)
                .filter(Objects::nonNull)
                .toList();
        if (userIds.isEmpty()) {
            return;
        }
        Map<String, Object> params = new HashMap<>(4);
        params.put("orderNo", order.getOrderNo());
        messageService.sendBatch(userIds, MessageType.ORDER_URGED, order.getId(), params);
    }

    /** 催单备注：过合规词表 + 规整空白；为空则记成「未填写」而不是 null */
    private static String normalizeRemark(String remark) {
        if (remark == null || remark.isBlank()) {
            return "家属催促尽快安排陪诊员";
        }
        String text = remark.trim();
        String hit = ComplianceCheckUtil.firstHit(text);
        if (hit != null) {
            throw new BusinessException(ResultCode.CONTENT_SENSITIVE,
                    "备注包含不合规表述（" + hit + "），请修改后重试");
        }
        return text;
    }
}
