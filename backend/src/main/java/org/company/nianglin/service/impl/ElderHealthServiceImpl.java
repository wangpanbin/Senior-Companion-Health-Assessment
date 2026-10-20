package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.DateRange;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.CheckinNode;
import org.company.nianglin.constant.Gender;
import org.company.nianglin.constant.HealthTimelineCategory;
import org.company.nianglin.constant.MedicationBucket;
import org.company.nianglin.constant.RelationType;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.dto.HealthTimelineQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.InternalMessage;
import org.company.nianglin.entity.MedicationTask;
import org.company.nianglin.entity.OrderCheckin;
import org.company.nianglin.entity.OrderReview;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.ElderHealthReadMapper;
import org.company.nianglin.mapper.InternalMessageMapper;
import org.company.nianglin.mapper.MedicationTaskMapper;
import org.company.nianglin.mapper.OrderCheckinMapper;
import org.company.nianglin.mapper.OrderReviewMapper;
import org.company.nianglin.mapper.SysUserMapper;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.ElderHealthService;
import org.company.nianglin.service.ElderService;
import org.company.nianglin.util.MaskUtil;
import org.company.nianglin.vo.ChartDataVO;
import org.company.nianglin.vo.ElderHealthOverviewVO;
import org.company.nianglin.vo.FamilyHealthSummaryVO;
import org.company.nianglin.vo.HealthPendingItemVO;
import org.company.nianglin.vo.HealthTimelineVO;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 老人健康协同中心的实现（V3.2 · FR-Overview / FR-Timeline / FR-Summary）。
 *
 * <h3>三件容易做错的事，这里都收住了</h3>
 *
 * <ol>
 *   <li><b>不 JOIN</b>：{@code order_checkin} 与 {@code internal_message} 都没有老人 ID，
 *       所以统一以「先取该老人的订单 ID 集合，再让子表用 {@code IN} 过滤」的方式取数，
 *       避免为每类事件各写一条 JOIN（那会让订单表被重复扫 N 次）。</li>
 *   <li><b>不建表</b>：本版零迁移。所有聚合都是 Service 层的只读计算
 *       （roadmap 决策 2.1 + G2 最小化迁移）。</li>
 *   <li><b>不吞异常</b>：任一子查询失败就让整个请求失败，不返回半截数据 ——
 *       半个健康概览比一个报错更容易误导人。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ElderHealthServiceImpl implements ElderHealthService {

    /** 时间线预览条数（概览里的「最近发生了什么」） */
    private static final int PREVIEW_LIMIT = 5;

    /** 时间线默认单页条数 */
    private static final int DEFAULT_LIMIT = 50;

    /** 每类事件的最大取数条数；超出按时间倒序截断 */
    private static final int MAX_EVENTS_PER_CATEGORY = 200;

    /** 单个老人参与的最大订单数（时间线取数上限） */
    private static final int MAX_ORDERS = 200;

    /** 游标里的时间格式 */
    private static final DateTimeFormatter CURSOR_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /** 家属端可以看到的站内信类型 */
    private static final List<String> FAMILY_MESSAGE_TYPES = List.of("ORDER_PROGRESS", "MEDICATION_REMIND");

    /** 老人端可以看到的站内信类型（ORDER_PROGRESS 是发给家属的，与老人无关） */
    private static final List<String> ELDER_MESSAGE_TYPES = List.of("MEDICATION_REMIND");

    private final ElderHealthReadMapper healthReadMapper;
    private final ElderService elderService;
    private final SysUserMapper sysUserMapper;
    private final CompanionOrderMapper companionOrderMapper;
    private final OrderCheckinMapper orderCheckinMapper;
    private final MedicationTaskMapper medicationTaskMapper;
    private final InternalMessageMapper internalMessageMapper;
    private final OrderReviewMapper orderReviewMapper;

    /* ================================================================== */
    /* 家庭健康协同首页摘要                                                */
    /* ================================================================== */

    @Override
    public List<FamilyHealthSummaryVO> familySummary() {
        Long familyId = SecurityUtils.currentUserId();
        List<Map<String, Object>> rows = healthReadMapper.selectFamilyElders(familyId);
        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> elderIds = rows.stream()
                .map(row -> toLong(row.get("elderId")))
                .filter(java.util.Objects::nonNull)
                .toList();
        if (elderIds.isEmpty()) {
            return List.of();
        }

        DateRange range = new DateRangeQuery().toRange();
        Map<Long, Map<String, Object>> todayMedication =
                indexByElder(healthReadMapper.selectTodayMedicationByElder(elderIds, LocalDate.now()));
        Map<Long, Map<String, Object>> visits =
                indexByElder(healthReadMapper.selectVisitStatsByElder(elderIds, range.startTime(), range.endTime()));
        Map<Long, Map<String, Object>> pending =
                indexByElder(healthReadMapper.selectPendingCountsByElder(elderIds));

        List<FamilyHealthSummaryVO> summaries = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Long elderId = toLong(row.get("elderId"));
            if (elderId == null) {
                continue;
            }
            Map<String, Object> med = todayMedication.get(elderId);
            Map<String, Object> visit = visits.get(elderId);
            Map<String, Object> wait = pending.get(elderId);

            FamilyHealthSummaryVO.TodayMedication today =
                    new FamilyHealthSummaryVO.TodayMedication()
                            .setTotal(toInt(med == null ? null : med.get("total")))
                            .setTaken(toInt(med == null ? null : med.get("taken")))
                            .setPending(toInt(med == null ? null : med.get("pending")));

            summaries.add(new FamilyHealthSummaryVO()
                    .setElderId(elderId)
                    .setElderName(MaskUtil.name(str(row.get("name"))))
                    .setRelationLabel(RelationType.labelOf(str(row.get("relation"))))
                    .setTodayMedication(today)
                    .setRecentVisitCount(toInt(visit == null ? null : visit.get("orderCount")))
                    .setPendingItemCount(pendingItemCount(wait, today))
                    .setLastActivityAt(toLocalDateTime(visit == null ? null : visit.get("lastActivityAt"))));
        }

        summaries.sort(Comparator
                .comparing(FamilyHealthSummaryVO::getLastActivityAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(FamilyHealthSummaryVO::getElderId));
        return summaries;
    }

    /* ================================================================== */
    /* 健康概览                                                            */
    /* ================================================================== */

    @Override
    public ElderHealthOverviewVO overview(Long elderId, DateRangeQuery query) {
        ElderProfile elder = elderService.requireAccessible(elderId);
        return buildOverview(elder, query, false);
    }

    @Override
    public ElderHealthOverviewVO selfOverview(DateRangeQuery query) {
        ElderProfile elder = requireSelfElder();
        return buildOverview(elder, query, true);
    }

    /**
     * 组装概览。
     *
     * @param elder    老人档案（已完成归属校验）
     * @param query    区间入参
     * @param selfMode 老人本人视角：去掉费用 / 催单 / 履约风险 / 家属姓名，只留与老人自身相关的事项
     */
    private ElderHealthOverviewVO buildOverview(ElderProfile elder, DateRangeQuery query, boolean selfMode) {
        Long elderId = elder.getId();
        DateRange range = query == null ? new DateRangeQuery().toRange() : query.toRange();
        List<Long> ids = List.of(elderId);

        Map<String, Object> medication = firstRow(
                healthReadMapper.selectMedicationBucketsByElder(ids, range.start(), range.end()));
        Map<String, Object> visit = firstRow(
                healthReadMapper.selectVisitStatsByElder(ids, range.startTime(), range.endTime()));
        Map<String, Object> pending = firstRow(healthReadMapper.selectPendingCountsByElder(ids));
        Map<String, Object> todayMedication = firstRow(
                healthReadMapper.selectTodayMedicationByElder(ids, LocalDate.now()));

        int total = toInt(medication == null ? null : medication.get("total"));
        int taken = toInt(medication == null ? null : medication.get("taken"));

        ElderHealthOverviewVO.Elder elderInfo = new ElderHealthOverviewVO.Elder()
                .setId(elderId)
                .setName(MaskUtil.name(elder.getName()))
                .setGender(elder.getGender())
                .setGenderLabel(Gender.labelOf(elder.getGender()))
                .setAge(ageOf(elder.getBirthDate()))
                .setRelationLabel(selfMode ? null : relationLabelOf(elderId));

        ElderHealthOverviewVO.Visit visitInfo = new ElderHealthOverviewVO.Visit()
                .setOrderCount(toInt(visit == null ? null : visit.get("orderCount")))
                .setCompanionCount(toInt(visit == null ? null : visit.get("companionCount")))
                .setLastVisitAt(toLocalDateTime(visit == null ? null : visit.get("lastVisitAt")));

        ElderHealthOverviewVO.Medication medicationInfo = new ElderHealthOverviewVO.Medication()
                .setTotal(total)
                .setTaken(taken)
                .setMissed(toInt(medication == null ? null : medication.get("missed")))
                .setPending(toInt(medication == null ? null : medication.get("pending")))
                .setCompletionRate(ChartDataVO.percent(taken, total));

        return new ElderHealthOverviewVO()
                .setElder(elderInfo)
                .setRange(rangeInfo(range))
                .setVisit(visitInfo)
                .setMedication(medicationInfo)
                .setPendingItems(pendingItems(elderId, pending, todayMedication, selfMode))
                .setTimelinePreview(previewItems(elder, range, selfMode));
    }

    /* ================================================================== */
    /* 健康时间线                                                          */
    /* ================================================================== */

    @Override
    public HealthTimelineVO timeline(Long elderId, HealthTimelineQuery query) {
        ElderProfile elder = elderService.requireAccessible(elderId);
        return buildTimeline(elder, query, false);
    }

    @Override
    public HealthTimelineVO selfTimeline(HealthTimelineQuery query) {
        ElderProfile elder = requireSelfElder();
        return buildTimeline(elder, query, true);
    }

    private HealthTimelineVO buildTimeline(ElderProfile elder, HealthTimelineQuery query, boolean selfMode) {
        Long elderId = elder.getId();
        DateRange range = query == null ? new DateRangeQuery().toRange() : query.toRange();
        Set<HealthTimelineCategory> categories = parseCategories(query == null ? null : query.getCategories());

        List<TimelineEvent> events = collectEvents(elder, range, categories, selfMode);
        events.sort(EVENT_ORDER);

        int limit = query == null || query.getLimit() == null ? DEFAULT_LIMIT : query.getLimit();
        List<TimelineEvent> page = events;
        String cursor = query == null ? null : query.getCursor();
        if (cursor != null && !cursor.isBlank()) {
            TimelineEvent cursorEvent = decodeCursor(cursor);
            page = events.stream()
                    .filter(event -> EVENT_ORDER.compare(event, cursorEvent) > 0)
                    .toList();
        }

        boolean hasMore = page.size() > limit;
        List<TimelineEvent> pageItems = hasMore ? page.subList(0, limit) : page;
        String nextCursor = hasMore && !pageItems.isEmpty()
                ? encodeCursor(pageItems.get(pageItems.size() - 1))
                : null;

        return new HealthTimelineVO()
                .setElderId(elderId)
                .setElderName(MaskUtil.name(elder.getName()))
                .setRange(rangeInfo(range))
                .setHasMore(hasMore)
                .setNextCursor(nextCursor)
                .setItems(pageItems.stream().map(this::toItem).toList());
    }

    /**
     * 收集四类事件。
     *
     * <p>订单是<b>唯一入口</b>：打卡与消息都靠订单 ID 集合定位到这位老人
     * （那两张表都没有老人 ID），所以只查一次订单。</p>
     */
    private List<TimelineEvent> collectEvents(ElderProfile elder, DateRange range,
                                               Set<HealthTimelineCategory> categories, boolean selfMode) {
        List<TimelineEvent> events = new ArrayList<>();
        Long elderId = elder.getId();

        List<CompanionOrder> orders = elderOrders(elderId, range);
        List<Long> orderIds = orders.stream().map(CompanionOrder::getId).toList();

        if (categories.contains(HealthTimelineCategory.ORDER)) {
            orderEvents(events, orders, range, selfMode);
            reviewEvents(events, orderIds, range, selfMode);
        }
        if (categories.contains(HealthTimelineCategory.CHECKIN)) {
            checkinEvents(events, orderIds, range);
        }
        if (categories.contains(HealthTimelineCategory.MEDICATION)) {
            medicationEvents(events, elderId, range);
        }
        if (categories.contains(HealthTimelineCategory.MESSAGE)) {
            messageEvents(events, elder, orderIds, range, selfMode);
        }
        return events;
    }

    /** 该老人参与、且与区间沾边的订单 */
    private List<CompanionOrder> elderOrders(Long elderId, DateRange range) {
        return companionOrderMapper.selectList(Wrappers.<CompanionOrder>lambdaQuery()
                .eq(CompanionOrder::getElderId, elderId)
                .and(w -> w.nested(n -> n.ge(CompanionOrder::getCreateTime, range.startTime())
                        .lt(CompanionOrder::getCreateTime, range.endExclusive()))
                        .or().nested(n -> n.ge(CompanionOrder::getVisitTime, range.startTime())
                                .lt(CompanionOrder::getVisitTime, range.endExclusive())))
                .orderByDesc(CompanionOrder::getCreateTime)
                .last("LIMIT " + MAX_ORDERS));
    }

    /** 订单状态事件：下单 / 已接单 / 开始服务 / 服务完成 / 已取消 */
    private void orderEvents(List<TimelineEvent> events, List<CompanionOrder> orders,
                              DateRange range, boolean selfMode) {
        for (CompanionOrder order : orders) {
            String detail = orderDetail(order);
            String link = orderLink(order.getId(), selfMode);
            addOrderEvent(events, order, order.getCreateTime(), "CREATED", "已下单", detail, order.getId(), link);
            addOrderEvent(events, order, order.getAcceptTime(), "ACCEPTED", "已接单", detail, order.getId(), link);
            addOrderEvent(events, order, order.getStartTime(), "STARTED", "开始服务", detail, order.getId(), link);
            addOrderEvent(events, order, order.getFinishTime(), "COMPLETED", "服务完成", detail, order.getId(), link);
            addOrderEvent(events, order, order.getCancelTime(), "CANCELLED", "已取消", detail, order.getId(), link);
        }
    }

    /** 评价完成事件 */
    private void reviewEvents(List<TimelineEvent> events, List<Long> orderIds, DateRange range, boolean selfMode) {
        if (orderIds.isEmpty()) {
            return;
        }
        List<OrderReview> reviews = orderReviewMapper.selectList(Wrappers.<OrderReview>lambdaQuery()
                .in(OrderReview::getOrderId, orderIds)
                .ge(OrderReview::getCreateTime, range.startTime())
                .lt(OrderReview::getCreateTime, range.endExclusive())
                .last("LIMIT " + MAX_EVENTS_PER_CATEGORY));
        for (OrderReview review : reviews) {
            String detail = review.getScore() == null ? null : "评分 " + review.getScore() + " 分";
            events.add(new TimelineEvent(
                    "ORDER:" + review.getId() + ":REVIEWED",
                    review.getCreateTime(),
                    HealthTimelineCategory.ORDER.name(),
                    "REVIEWED",
                    "已评价",
                    "订单已完成评价",
                    detail,
                    null,
                    review.getOrderId(),
                    null,
                    orderLink(review.getOrderId(), selfMode)));
        }
    }

    /** 陪诊打卡事件 */
    private void checkinEvents(List<TimelineEvent> events, List<Long> orderIds, DateRange range) {
        if (orderIds.isEmpty()) {
            return;
        }
        List<OrderCheckin> checkins = orderCheckinMapper.selectList(Wrappers.<OrderCheckin>lambdaQuery()
                .in(OrderCheckin::getOrderId, orderIds)
                .ge(OrderCheckin::getCheckinTime, range.startTime())
                .lt(OrderCheckin::getCheckinTime, range.endExclusive())
                .orderByDesc(OrderCheckin::getCheckinTime)
                .last("LIMIT " + MAX_EVENTS_PER_CATEGORY));
        for (OrderCheckin checkin : checkins) {
            if (checkin.getCheckinTime() == null) {
                continue;
            }
            String nodeLabel = CheckinNode.labelOf(checkin.getNode());
            events.add(new TimelineEvent(
                    "CHECKIN:" + checkin.getId(),
                    checkin.getCheckinTime(),
                    HealthTimelineCategory.CHECKIN.name(),
                    checkin.getNode(),
                    nodeLabel,
                    "陪诊打卡 · " + nodeLabel,
                    truncate(checkin.getRemark(), 50),
                    Integer.valueOf(1).equals(checkin.getIsAbnormal()),
                    checkin.getOrderId(),
                    null,
                    null));
        }
    }

    /** 用药事件：已服按确认时间、漏服按计划时间；待服不是事件（它属于待处理事项） */
    private void medicationEvents(List<TimelineEvent> events, Long elderId, DateRange range) {
        List<MedicationTask> tasks = medicationTaskMapper.selectList(Wrappers.<MedicationTask>lambdaQuery()
                .eq(MedicationTask::getElderId, elderId)
                .ge(MedicationTask::getPlanTime, range.startTime())
                .lt(MedicationTask::getPlanTime, range.endExclusive())
                .orderByDesc(MedicationTask::getPlanTime)
                .last("LIMIT " + MAX_EVENTS_PER_CATEGORY));
        for (MedicationTask task : tasks) {
            MedicationBucket bucket = MedicationBucket.of(task.getStatus(),
                    task.getWasMissed() != null && task.getWasMissed() == 1);
            if (bucket == MedicationBucket.PENDING) {
                continue;
            }
            LocalDateTime occurredAt = bucket == MedicationBucket.TAKEN
                    ? (task.getConfirmTime() == null ? task.getPlanTime() : task.getConfirmTime())
                    : task.getPlanTime();
            String type = bucket == MedicationBucket.TAKEN ? "TAKEN" : "MISSED";
            String title = bucket == MedicationBucket.TAKEN ? "已服药" : "服药任务未确认";
            events.add(new TimelineEvent(
                    "MEDICATION:" + task.getId(),
                    occurredAt,
                    HealthTimelineCategory.MEDICATION.name(),
                    type,
                    bucket.getLabel(),
                    title,
                    medicationDetail(task),
                    null,
                    null,
                    task.getId(),
                    "/elder/medication"));
        }
    }

    /** 站内信事件：只取发给当前读者本人的进度与用药提醒 */
    private void messageEvents(List<TimelineEvent> events, ElderProfile elder, List<Long> orderIds,
                               DateRange range, boolean selfMode) {
        Long receiverId = selfMode ? elder.getUserId() : SecurityUtils.currentUserId();
        if (receiverId == null) {
            return;
        }
        List<String> types = selfMode ? ELDER_MESSAGE_TYPES : FAMILY_MESSAGE_TYPES;
        List<InternalMessage> messages = internalMessageMapper.selectList(Wrappers.<InternalMessage>lambdaQuery()
                .eq(InternalMessage::getReceiverId, receiverId)
                .in(InternalMessage::getType, types)
                .ge(InternalMessage::getCreateTime, range.startTime())
                .lt(InternalMessage::getCreateTime, range.endExclusive())
                .orderByDesc(InternalMessage::getCreateTime)
                .last("LIMIT " + MAX_EVENTS_PER_CATEGORY));
        for (InternalMessage message : messages) {
            boolean orderMessage = "ORDER_PROGRESS".equals(message.getType());
            if (orderMessage && !orderIds.contains(message.getBizId())) {
                continue;
            }
            if (!orderMessage && !elder.getId().equals(message.getBizId())) {
                continue;
            }
            events.add(new TimelineEvent(
                    "MESSAGE:" + message.getId(),
                    message.getCreateTime(),
                    HealthTimelineCategory.MESSAGE.name(),
                    message.getType(),
                    message.getTitle(),
                    message.getTitle(),
                    truncate(message.getContent(), 80),
                    null,
                    orderMessage ? message.getBizId() : null,
                    null,
                    orderMessage ? orderLink(message.getBizId(), selfMode) : "/elder/medication"));
        }
    }

    /* ================================================================== */
    /* 待处理事项                                                          */
    /* ================================================================== */

    /**
     * 待处理事项：纯派生、零新表。
     *
     * <p>只陈述事实与数量，不做任何健康判断（合规红线-1）。</p>
     */
    private List<HealthPendingItemVO> pendingItems(Long elderId, Map<String, Object> pending,
                                                    Map<String, Object> todayMedication, boolean selfMode) {
        List<HealthPendingItemVO> items = new ArrayList<>();

        int medicationPending = toInt(todayMedication == null ? null : todayMedication.get("pending"));
        if (medicationPending > 0) {
            items.add(new HealthPendingItemVO()
                    .setType("MEDICATION_PENDING")
                    .setTypeLabel("今日有服药任务未确认")
                    .setCount(medicationPending)
                    .setLinkUrl(selfMode ? "/elder/medication" : "/family/medication"));
        }

        if (selfMode) {
            // 老人端只保留与自身相关的事项：订单等待接单、待评价、履约风险都属于家属的事
            return items;
        }

        int waiting = toInt(pending == null ? null : pending.get("waitingOrders"));
        if (waiting > 0) {
            items.add(new HealthPendingItemVO()
                    .setType("ORDER_WAITING")
                    .setTypeLabel("有订单在等待接单")
                    .setCount(waiting)
                    .setLinkUrl("/family/order"));
        }

        int toReview = toInt(pending == null ? null : pending.get("toReviewOrders"));
        if (toReview > 0) {
            items.add(new HealthPendingItemVO()
                    .setType("ORDER_TO_REVIEW")
                    .setTypeLabel("有已完成订单待评价")
                    .setCount(toReview)
                    .setLinkUrl("/family/order"));
        }

        int risk = toInt(pending == null ? null : pending.get("riskOrders"));
        if (risk > 0) {
            items.add(new HealthPendingItemVO()
                    .setType("FULFILLMENT_RISK")
                    .setTypeLabel("有订单需要平台协调")
                    .setCount(risk)
                    .setLinkUrl("/family/order"));
        }
        return items;
    }

    /** 待处理事项条数：与 {@link #pendingItems} 同源，保证首页角标与详情一致 */
    private int pendingItemCount(Map<String, Object> pending, FamilyHealthSummaryVO.TodayMedication today) {
        int count = 0;
        if (today != null && today.getPending() != null && today.getPending() > 0) {
            count++;
        }
        if (pending == null) {
            return count;
        }
        if (toInt(pending.get("waitingOrders")) > 0) {
            count++;
        }
        if (toInt(pending.get("toReviewOrders")) > 0) {
            count++;
        }
        if (toInt(pending.get("riskOrders")) > 0) {
            count++;
        }
        return count;
    }

    /* ================================================================== */
    /* 内部工具                                                            */
    /* ================================================================== */

    /** 时间线预览（概览里的「最近发生了什么」） */
    private List<HealthTimelineVO.Item> previewItems(ElderProfile elder, DateRange range, boolean selfMode) {
        List<TimelineEvent> events = collectEvents(elder, range,
                Set.of(HealthTimelineCategory.ORDER, HealthTimelineCategory.CHECKIN,
                        HealthTimelineCategory.MEDICATION, HealthTimelineCategory.MESSAGE),
                selfMode);
        events.sort(EVENT_ORDER);
        return events.stream().limit(PREVIEW_LIMIT).map(this::toItem).toList();
    }

    /** 老人本人：先取自己的档案，取不到（未建档）报 2001 */
    private ElderProfile requireSelfElder() {
        SysUser user = sysUserMapper.selectById(SecurityUtils.currentUserId());
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        Long elderId = elderService.elderIdOf(user);
        if (elderId == null) {
            throw new BusinessException(ResultCode.ELDER_NOT_FOUND, "尚未建立老人档案");
        }
        return elderService.requireAccessible(elderId);
    }

    /** 解析事件分类过滤条件；非法值报 400 */
    private Set<HealthTimelineCategory> parseCategories(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of(HealthTimelineCategory.values());
        }
        Set<HealthTimelineCategory> categories = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            HealthTimelineCategory category = HealthTimelineCategory.of(name);
            if (category == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "事件分类取值不合法：" + name);
            }
            categories.add(category);
        }
        if (categories.isEmpty()) {
            return Set.of(HealthTimelineCategory.values());
        }
        return categories;
    }

    private void addOrderEvent(List<TimelineEvent> events, CompanionOrder order, LocalDateTime occurredAt,
                                String type, String typeLabel, String detail, Long orderId, String link) {
        if (occurredAt == null) {
            return;
        }
        events.add(new TimelineEvent(
                "ORDER:" + order.getId() + ":" + type,
                occurredAt,
                HealthTimelineCategory.ORDER.name(),
                type,
                typeLabel,
                typeLabel,
                detail,
                null,
                orderId,
                null,
                link));
    }

    /** 订单事件的补充说明：医院 + 科室；不含任何金额 */
    private String orderDetail(CompanionOrder order) {
        String hospital = order.getHospital();
        String department = order.getDepartment();
        if (hospital == null || hospital.isBlank()) {
            return department == null || department.isBlank() ? null : department;
        }
        if (department == null || department.isBlank()) {
            return hospital;
        }
        return hospital + " · " + department;
    }

    /** 用药事件的补充说明：药品名 + 单次用量（家属按医嘱录入的原值，不是系统建议） */
    private String medicationDetail(MedicationTask task) {
        String name = task.getMedicineName();
        String dosage = task.getDosage();
        if (name == null || name.isBlank()) {
            return dosage == null || dosage.isBlank() ? null : dosage;
        }
        if (dosage == null || dosage.isBlank()) {
            return name;
        }
        return name + " · " + dosage;
    }

    /** 订单跳转链接；老人端没有订单详情页，订单事件不给链接 */
    private String orderLink(Long orderId, boolean selfMode) {
        if (selfMode || orderId == null) {
            return null;
        }
        return "/family/order/" + orderId;
    }

    /** 关系标签 */
    private String relationLabelOf(Long elderId) {
        List<Map<String, Object>> rows =
                healthReadMapper.selectFamilyElders(SecurityUtils.currentUserId());
        return rows.stream()
                .filter(row -> elderId.equals(toLong(row.get("elderId"))))
                .map(row -> RelationType.labelOf(str(row.get("relation"))))
                .findFirst()
                .orElse(null);
    }

    private HealthTimelineVO.Item toItem(TimelineEvent event) {
        return new HealthTimelineVO.Item()
                .setEventId(event.eventId())
                .setCategory(event.category())
                .setCategoryLabel(HealthTimelineCategory.of(event.category()) == null
                        ? event.category() : HealthTimelineCategory.of(event.category()).getLabel())
                .setType(event.type())
                .setTypeLabel(event.typeLabel())
                .setOccurredAt(event.occurredAt())
                .setTitle(event.title())
                .setDetail(event.detail())
                .setAbnormal(event.abnormal())
                .setOrderId(event.orderId())
                .setMedicationTaskId(event.medicationTaskId())
                .setLinkUrl(event.linkUrl());
    }

    private HealthTimelineVO.Range rangeInfo(DateRange range) {
        return new HealthTimelineVO.Range()
                .setStartDate(range.start())
                .setEndDate(range.end())
                .setDays((int) range.days());
    }

    private String encodeCursor(TimelineEvent event) {
        String raw = event.occurredAt().format(CURSOR_TIME) + "|" + event.eventId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private TimelineEvent decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int at = raw.indexOf('|');
            if (at <= 0) {
                throw new IllegalArgumentException("游标格式不合法");
            }
            LocalDateTime occurredAt = LocalDateTime.parse(raw.substring(0, at), CURSOR_TIME);
            return new TimelineEvent(raw.substring(at + 1), occurredAt, null, null, null,
                    null, null, null, null, null, null);
        } catch (RuntimeException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "游标不合法");
        }
    }

    private Map<Long, Map<String, Object>> indexByElder(List<Map<String, Object>> rows) {
        return rows.stream()
                .filter(row -> toLong(row.get("elderId")) != null)
                .collect(Collectors.toMap(row -> toLong(row.get("elderId")), Function.identity(),
                        (a, b) -> a));
    }

    private Map<String, Object> firstRow(List<Map<String, Object>> rows) {
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private static Integer ageOf(LocalDate birthDate) {
        if (birthDate == null) {
            return null;
        }
        return Period.between(birthDate, LocalDate.now()).getYears();
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static int toInt(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }

    /* ================================================================== */
    /* 内部模型                                                            */
    /* ================================================================== */

    /**
     * 时间线事件（内部模型）。
     *
     * <p>{@code eventId} 全局唯一：{@code 分类:来源ID:类型}。
     * 排序键是 {@code (发生时间倒序, eventId 升序)}，
     * 游标就是这两个值的编码 —— 翻页与排序用的是同一套比较器，
     * 所以不会出现「第二页漏掉或重复第一条」。</p>
     */
    private record TimelineEvent(String eventId, LocalDateTime occurredAt, String category, String type,
                                  String typeLabel, String title, String detail, Boolean abnormal,
                                  Long orderId, Long medicationTaskId, String linkUrl) {
    }

    /** 事件排序：时间倒序 → eventId 升序 */
    private static final Comparator<TimelineEvent> EVENT_ORDER =
            Comparator.comparing(TimelineEvent::occurredAt).reversed()
                    .thenComparing(TimelineEvent::eventId);
}
