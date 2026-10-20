package org.company.nianglin.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.DateRange;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.constant.OperationsGapRange;
import org.company.nianglin.constant.OperationsTrendDimension;
import org.company.nianglin.constant.OperationsTrendRange;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.dto.OperationsEffectQuery;
import org.company.nianglin.dto.OperationsGapQuery;
import org.company.nianglin.dto.OperationsTrendQuery;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.OperationsMapper;
import org.company.nianglin.service.AdminService;
import org.company.nianglin.service.OperationsService;
import org.company.nianglin.service.support.matching.LegacySlotParser;
import org.company.nianglin.service.support.matching.MatchingCandidate;
import org.company.nianglin.service.support.metrics.MetricCalculator;
import org.company.nianglin.service.support.metrics.MetricCalculatorRegistry;
import org.company.nianglin.service.support.metrics.MetricValues;
import org.company.nianglin.util.MetricDictionary;
import org.company.nianglin.vo.ChartDataVO;
import org.company.nianglin.vo.FulfillmentSummaryVO;
import org.company.nianglin.vo.OperationsEffectVO;
import org.company.nianglin.vo.OperationsExceptionItemVO;
import org.company.nianglin.vo.OperationsExceptionVO;
import org.company.nianglin.vo.OperationsGapVO;
import org.company.nianglin.vo.OperationsHealthVO;
import org.company.nianglin.vo.OperationsOverviewVO;
import org.company.nianglin.vo.OperationsTrendVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 平台运营驾驶舱的实现（V3.5 · T3）。
 *
 * <h3>本类在防四件事</h3>
 *
 * <ol>
 *   <li><b>风险判定不另建一套</b>：异常中心的前两项直接取
 *       {@code AdminService#fulfillmentSummary()}（决策 5.1）。
 *       「另算一套」是本版最容易犯且最难发现的错 —— 两个页面的数字会差几单，
 *       而谁也说不清哪个是对的。</li>
 *   <li><b>「履约率」与「完成率（按时）」分家</b>（偏差 D-3.5-1）：
 *       履约率 = 完成 ÷（完成 + 取消）；完成率 = 按时完成 ÷ 已完成。
 *       两个名字一个数是口径分裂的另一种形态。</li>
 *   <li><b>空数据返回 0 / 空序列</b>，不返回 {@code null}、不抛异常。</li>
 *   <li><b>趋势桶必须补齐</b>：{@code categories} 与每条 {@code series.data}
 *       长度严格一致，否则 ECharts 会静默跳段 —— 图看着对，其实少了一段。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V3.5
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OperationsServiceImpl implements OperationsService {

    /** 异常中心「用药漏服异常」最多回看多少条最近漏服订单 */
    private static final int RECENT_MISSED_ORDER_LIMIT = 10;

    /** 趋势粒度：驾驶舱固定按天 */
    private static final String GRANULARITY_DAY = "DAY";

    /* 四类异常的处理入口（前端真实存在的路由，见 tickets 偏差 D-3.5-2） */
    private static final String LINK_FULFILLMENT = "/admin/fulfillment";
    private static final String LINK_COMPLAINT = "/admin/complaint";
    private static final String LINK_ORDER = "/admin/order";

    /** 桶的形状：7 天 × 24 小时（与统计区间长度无关） */
    private static final int GAP_WEEKDAYS = 7;
    private static final int GAP_HOURS = 24;

    /** 指标字典（人读面的机器副本）：指标中文名只能从这里取，不另写一份名表 */
    private static final MetricDictionary DICTIONARY = MetricDictionary.load();

    private final OperationsMapper operationsMapper;

    /** 复用 V3.1 的履约风险判定结果（决策 5.1） */
    private final AdminService adminService;

    private final OperationsProperties operationsProperties;

    /** 指标计算器注册表（V3.6）：效果对比只认注册表里有的指标 */
    private final MetricCalculatorRegistry metricCalculatorRegistry;

    /* ================================================================== */
    /* 1 · 概览                                                            */
    /* ================================================================== */

    @Override
    public OperationsOverviewVO overview(DateRangeQuery query) {
        DateRange range = range(query);

        Map<String, Object> orderStats = orderStats(range);
        Map<String, Object> complaintStats = operationsMapper.selectComplaintStats(
                range.startTime(), range.endTime());
        Map<String, Object> reviewStats = operationsMapper.selectReviewStats(
                range.startTime(), range.endTime());

        long completed = longOf(orderStats, "completedCount");
        long cancelled = longOf(orderStats, "cancelledCount");
        long total = longOf(orderStats, "totalCount");

        return new OperationsOverviewVO()
                .setPeriodStart(range.start().toString())
                .setPeriodEnd(range.end().toString())
                .setTodayOrderCount((int) longOf(orderStats, "todayCount"))
                .setCompletedOrderCount((int) completed)
                .setInServiceCount((int) longOf(orderStats, "inServiceCount"))
                .setPendingCount((int) longOf(orderStats, "pendingCount"))
                // 履约率：完成 ÷（完成 + 取消）。与「完成率（按时）」不是一个数。
                .setFulfillmentRate(ChartDataVO.percent(completed, completed + cancelled))
                .setAvgAcceptMinutes((int) longOf(orderStats, "avgAcceptMinutes"))
                // 投诉率：投诉<b>订单</b>数 ÷ 区间订单数（同一单多次投诉只算一单）
                .setComplaintRate(ChartDataVO.percent(longOf(complaintStats, "complaintOrderCount"), total))
                .setAverageScore(averageScore(reviewStats));
    }

    /* ================================================================== */
    /* 2 · 异常中心                                                        */
    /* ================================================================== */

    @Override
    public OperationsExceptionVO exceptions(DateRangeQuery query) {
        DateRange range = range(query);

        /* ---------- ① ② 复用 V3.1 履约风险中心（决策 5.1） ---------- */
        FulfillmentSummaryVO summary = adminService.fulfillmentSummary();
        int manualRequired = summary == null ? 0 : summary.getManualRequired();
        int matchWarning = summary == null ? 0 : summary.getWarning() + summary.getUrgent();

        /* ---------- ③ 投诉率异常陪诊员 ---------- */
        int abnormalCompanion = abnormalCompanionCount();

        /* ---------- ④ 用药漏服异常 ---------- */
        Integer missed = operationsMapper.selectMissedMedicationCount(range.startTime(), range.endTime());
        int missedCount = missed == null ? 0 : missed;

        List<OperationsExceptionItemVO> items = List.of(
                item("MANUAL_REQUIRED", "需人工介入订单", manualRequired, "DANGER", LINK_FULFILLMENT, null,
                        "履约风险已达人工介入阈值，需要管理员协调"),
                item("MATCH_WARNING", "超匹配预警时间订单", matchWarning, "WARNING", LINK_FULFILLMENT, null,
                        "等待时间超过预警阈值，系统正在扩大匹配范围"),
                item("COMPANION_COMPLAINT_RATE", "投诉率异常陪诊员", abnormalCompanion, "WARNING",
                        LINK_COMPLAINT, null,
                        "回溯期内投诉率超过阈值，需关注服务质量"),
                item("MEDICATION_MISSED", "用药漏服异常", missedCount, "WARNING", LINK_ORDER,
                        recentMissedOrderId(range),
                        missedDescription(missedCount)));

        int total = items.stream().mapToInt(OperationsExceptionItemVO::getCount).sum();
        return new OperationsExceptionVO()
                .setTotal(total)
                .setItems(items);
    }

    /**
     * 投诉率异常的陪诊员数量。
     *
     * <p>判定：回溯期内接单量 ≥ {@code complaint-min-orders}（样本太小不判定）
     * <b>且</b>投诉率严格大于 {@code complaint-rate-threshold}。</p>
     */
    private int abnormalCompanionCount() {
        int lookbackDays = operationsProperties.getComplaintLookbackDays();
        int minOrders = operationsProperties.getComplaintMinOrders();
        double threshold = operationsProperties.getComplaintRateThreshold();

        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(lookbackDays - 1L);
        List<Map<String, Object>> rows = operationsMapper.selectCompanionComplaintRates(
                start.atStartOfDay(), end.atTime(23, 59, 59));
        if (rows == null || rows.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (Map<String, Object> row : rows) {
            long orderCount = longOf(row, "orderCount");
            if (orderCount < minOrders) {
                // 接单量太小，比率没有意义：接过 1 单被投诉 1 次是 100%，不是「服务质量差」
                continue;
            }
            long complaintCount = longOf(row, "complaintCount");
            if ((double) complaintCount / orderCount > threshold) {
                count++;
            }
        }
        return count;
    }

    /** 最近一条漏服任务所属的订单 ID；没有则 {@code null} */
    private Long recentMissedOrderId(DateRange range) {
        List<Map<String, Object>> rows = operationsMapper.selectRecentMissedOrders(
                range.startTime(), range.endTime(), RECENT_MISSED_ORDER_LIMIT);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Object orderId = rows.get(0).get("orderId");
        if (orderId instanceof Number number) {
            return number.longValue();
        }
        return null;
    }

    /** 装配一个异常条目 */
    private OperationsExceptionItemVO item(String type, String typeLabel, int count, String level,
                                           String linkUrl, Long referenceId, String description) {
        return new OperationsExceptionItemVO()
                .setType(type)
                .setTypeLabel(typeLabel)
                .setCount(count)
                .setLevel(level)
                .setLinkUrl(linkUrl)
                .setReferenceId(referenceId)
                .setDescription(description);
    }

    /**
     * 「用药漏服异常」的说明文案。
     *
     * <p>阈值（{@code nianglin.operations.medication-missed-threshold}）决定这句话
     * 说的是「已经超阈值」还是「还没超阈值」——
     * 一张在 0 单时也写着「超过阈值」的卡片，管理员看久了就不会再相信它了。</p>
     */
    private String missedDescription(int missedCount) {
        if (missedCount >= operationsProperties.getMedicationMissedThreshold()) {
            return "区间内漏服任务数超过阈值，最近一条见最近订单";
        }
        return "区间内漏服任务数未超过阈值，最近一条见最近订单";
    }

    /* ================================================================== */
    /* 3 · 趋势                                                            */
    /* ================================================================== */

    @Override
    public OperationsTrendVO trend(OperationsTrendQuery query) {
        // 先校验再查库：非法维度不该产生任何 SQL
        OperationsTrendDimension dimension = dimension(query);
        OperationsTrendRange rangeType = trendRange(query);
        DateRange range = resolveRange(rangeType, query);

        TreeMap<LocalDate, BigDecimal> buckets = emptyAxis(range);
        switch (dimension) {
            case ORDER, COMPANION_ACTIVE, FULFILLMENT_RATE -> fillOrderBuckets(buckets, dimension, range);
            case COMPLAINT -> fillComplaintBuckets(buckets, range);
            case REVIEW -> fillReviewBuckets(buckets, range);
            case MEDICATION -> fillMedicationBuckets(buckets, range);
        }

        List<String> categories = new ArrayList<>(buckets.size());
        List<BigDecimal> data = new ArrayList<>(buckets.size());
        for (Map.Entry<LocalDate, BigDecimal> entry : buckets.entrySet()) {
            categories.add(entry.getKey().toString());
            data.add(entry.getValue());
        }

        OperationsTrendVO.Series series = new OperationsTrendVO.Series();
        series.setName(dimension.getLabel());
        series.setData(data);

        return new OperationsTrendVO()
                .setDimension(dimension.name())
                .setRange(rangeType.name())
                .setGranularity(GRANULARITY_DAY)
                .setPeriodStart(range.start().toString())
                .setPeriodEnd(range.end().toString())
                .setCategories(categories)
                .setSeries(List.of(series));
    }

    /** 订单族三条维度：订单量 / 活跃陪诊员数 / 履约率 */
    private void fillOrderBuckets(TreeMap<LocalDate, BigDecimal> buckets,
                                  OperationsTrendDimension dimension, DateRange range) {
        List<Map<String, Object>> rows =
                operationsMapper.selectDailyOrderTrend(range.startTime(), range.endTime());
        if (rows == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            LocalDate date = dateOf(row.get("d"));
            if (date == null || !buckets.containsKey(date)) {
                continue;
            }
            long completed = longOf(row, "completedCount");
            long cancelled = longOf(row, "cancelledCount");
            BigDecimal value = switch (dimension) {
                case ORDER -> count(longOf(row, "totalCount"));
                case COMPANION_ACTIVE -> count(longOf(row, "activeCompanionCount"));
                case FULFILLMENT_RATE -> percentValue(completed, completed + cancelled);
                default -> throw new IllegalStateException("非订单族维度不应进入订单桶");
            };
            buckets.put(date, value);
        }
    }

    /** 投诉维度 */
    private void fillComplaintBuckets(TreeMap<LocalDate, BigDecimal> buckets, DateRange range) {
        List<Map<String, Object>> rows =
                operationsMapper.selectDailyComplaintTrend(range.startTime(), range.endTime());
        if (rows == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            LocalDate date = dateOf(row.get("d"));
            if (date == null || !buckets.containsKey(date)) {
                continue;
            }
            buckets.put(date, count(longOf(row, "cnt")));
        }
    }

    /** 评价维度 */
    private void fillReviewBuckets(TreeMap<LocalDate, BigDecimal> buckets, DateRange range) {
        List<Map<String, Object>> rows =
                operationsMapper.selectDailyReviewTrend(range.startTime(), range.endTime());
        if (rows == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            LocalDate date = dateOf(row.get("d"));
            if (date == null || !buckets.containsKey(date)) {
                continue;
            }
            buckets.put(date, count(longOf(row, "cnt")));
        }
    }

    /** 用药任务维度 */
    private void fillMedicationBuckets(TreeMap<LocalDate, BigDecimal> buckets, DateRange range) {
        List<Map<String, Object>> rows =
                operationsMapper.selectDailyMedicationTrend(range.startTime(), range.endTime());
        if (rows == null) {
            return;
        }
        for (Map<String, Object> row : rows) {
            LocalDate date = dateOf(row.get("d"));
            if (date == null || !buckets.containsKey(date)) {
                continue;
            }
            buckets.put(date, count(longOf(row, "total")));
        }
    }

    /**
     * 构造完整的时间轴，所有桶初始为 0。
     *
     * <p>与 {@code StatisticsServiceImpl#emptyAxis} 同一条纪律：先按区间逐日建桶，
     * 再用查询结果累加。反过来做（先查有数据的日期、再补缺的）需要 diff 两次集合，
     * 而且一旦漏补就是静默的折线跳段。</p>
     */
    private TreeMap<LocalDate, BigDecimal> emptyAxis(DateRange range) {
        TreeMap<LocalDate, BigDecimal> buckets = new TreeMap<>();
        for (LocalDate date = range.start(); !date.isAfter(range.end()); date = date.plusDays(1)) {
            buckets.put(date, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        }
        return buckets;
    }

    /* ================================================================== */
    /* 4 · 健康度                                                          */
    /* ================================================================== */

    @Override
    public OperationsHealthVO health(DateRangeQuery query) {
        DateRange range = range(query);

        Map<String, Object> orderStats = orderStats(range);
        Map<String, Object> companionStats = operationsMapper.selectCompanionStats(
                range.startTime(), range.endTime());
        Map<String, Object> complaintStats = operationsMapper.selectComplaintStats(
                range.startTime(), range.endTime());
        Map<String, Object> reviewStats = operationsMapper.selectReviewStats(
                range.startTime(), range.endTime());

        long completed = longOf(orderStats, "completedCount");
        long cancelled = longOf(orderStats, "cancelledCount");
        long total = longOf(orderStats, "totalCount");
        long onTime = longOf(orderStats, "onTimeCompletedCount");

        return new OperationsHealthVO()
                .setFulfillmentRate(ChartDataVO.percent(completed, completed + cancelled))
                // 健康度六项统一用字符串渲染（与概览的 Integer 版本不同型，见 VO 注释）
                .setAvgAcceptMinutes(String.valueOf(longOf(orderStats, "avgAcceptMinutes")))
                // 完成率（按时）：按时完成 ÷ 已完成。与履约率不是一个数。
                .setCompletionRate(ChartDataVO.percent(onTime, completed))
                .setComplaintRate(ChartDataVO.percent(longOf(complaintStats, "complaintOrderCount"), total))
                .setAverageScore(averageScore(reviewStats))
                .setActiveCompanionCount((int) longOf(companionStats, "activeCompanionCount"));
    }

    /* ================================================================== */
    /* 5 · 供给时段缺口（V3.6 · T4）                                        */
    /* ================================================================== */

    @Override
    public OperationsGapVO gap(OperationsGapQuery query) {
        DateRange range = resolveGapRange(query);

        /* ---------- ① 需求侧：至今无人接的订单，按就诊时间落桶 ---------- */
        Map<String, Integer> pendingByBucket = new HashMap<>();
        List<Map<String, Object>> pendingRows = operationsMapper.selectPendingOrderVisitTimes(
                range.startTime(), range.endTime());
        if (pendingRows != null) {
            for (Map<String, Object> row : pendingRows) {
                LocalDateTime visitTime = localDateTimeOf(row.get("visitTime"));
                if (visitTime == null) {
                    continue;
                }
                pendingByBucket.merge(
                        bucketKey(visitTime.getDayOfWeek().getValue(), visitTime.getHour()), 1, Integer::sum);
            }
        }

        /* ---------- ② 供给侧：可服务时段 → 每个桶里「能服务这一格的人」 ---------- */
        Map<Long, List<MatchingCandidate.AvailableSlot>> structuredSlots = new LinkedHashMap<>();
        List<Map<String, Object>> slotRows = operationsMapper.selectCompanionServiceSlots();
        if (slotRows != null) {
            for (Map<String, Object> row : slotRows) {
                Long companionId = longOrNull(row.get("companionId"));
                Integer weekday = intOrNull(row.get("weekday"));
                LocalTime start = localTimeOf(row.get("startTime"));
                LocalTime end = localTimeOf(row.get("endTime"));
                if (companionId == null || weekday == null || start == null || end == null) {
                    continue;
                }
                structuredSlots.computeIfAbsent(companionId, key -> new ArrayList<>())
                        .add(new MatchingCandidate.AvailableSlot(weekday, start, end));
            }
        }

        Map<Long, String> legacyTexts = new LinkedHashMap<>();
        List<Map<String, Object>> profileRows = operationsMapper.selectCompanionAvailableTimes();
        if (profileRows != null) {
            for (Map<String, Object> row : profileRows) {
                Long companionId = longOrNull(row.get("companionId"));
                if (companionId == null) {
                    continue;
                }
                Object text = row.get("availableTime");
                legacyTexts.put(companionId, text == null ? null : String.valueOf(text));
            }
        }

        // 桶 → 该桶可服务的陪诊员集合。用 Set 是为了「同一人在同一桶里只计 1 次」
        Map<String, Set<Long>> supplyByBucket = new HashMap<>();
        int companionsWithoutSlots = 0;

        Set<Long> universe = new LinkedHashSet<>();
        universe.addAll(structuredSlots.keySet());
        universe.addAll(legacyTexts.keySet());
        for (Long companionId : universe) {
            List<MatchingCandidate.AvailableSlot> slots = structuredSlots.get(companionId);
            if (slots == null || slots.isEmpty()) {
                // 结构化表里没有 → 回退解析存量自由文本（V3.4 的回退路径）
                slots = LegacySlotParser.parse(legacyTexts.get(companionId));
            }
            if (slots.isEmpty()) {
                // 完全解析不出时段：不进任何桶，如实计数披露
                companionsWithoutSlots++;
                continue;
            }
            for (MatchingCandidate.AvailableSlot slot : slots) {
                for (int hour : hoursOf(slot)) {
                    supplyByBucket.computeIfAbsent(bucketKey(slot.weekday(), hour), key -> new HashSet<>())
                            .add(companionId);
                }
            }
        }

        /* ---------- ③ 168 个桶逐个补齐（空桶也要有行） ---------- */
        List<OperationsGapVO.Item> items = new ArrayList<>(GAP_WEEKDAYS * GAP_HOURS);
        for (int weekday = 1; weekday <= GAP_WEEKDAYS; weekday++) {
            for (int hour = 0; hour < GAP_HOURS; hour++) {
                String key = bucketKey(weekday, hour);
                int pending = pendingByBucket.getOrDefault(key, 0);
                Set<Long> available = supplyByBucket.get(key);
                int supply = available == null ? 0 : available.size();
                items.add(new OperationsGapVO.Item()
                        .setWeekday(weekday)
                        .setHour(hour)
                        .setPendingUnaccepted(pending)
                        .setAvailableCompanions(supply)
                        // 缺口 = 需求 − 供给；> 0 即为缺口（负数说明供给多于需求）
                        .setGap(pending - supply));
            }
        }

        return new OperationsGapVO()
                .setPeriodStart(range.start().toString())
                .setPeriodEnd(range.end().toString())
                .setTotal(items.size())
                .setCompanionsWithoutSlots(companionsWithoutSlots)
                .setItems(items);
    }

    /* ================================================================== */
    /* 6 · 干预效果对比（V3.6 · T5）                                        */
    /* ================================================================== */

    @Override
    public OperationsEffectVO effect(OperationsEffectQuery query) {
        String metricKey = query == null ? null : trimToNull(query.getMetric());
        if (metricKey == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "指标 key 不能为空");
        }
        // 指标必须在注册表里：没注册的指标一律 400，绝不「猜一个算法算出来」
        MetricCalculator calculator = metricCalculatorRegistry.find(metricKey);
        if (calculator == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "指标未登记或不可计算：" + metricKey);
        }

        DateRange current = currentRange(query);
        DateRange previous = previousRange(query, current);

        // 两期由**同一个**计算器算两遍（决策 6.2）：不是两边各写一遍公式
        String currentValue = calculator.calculate(current);
        String previousValue = calculator.calculate(previous);

        return new OperationsEffectVO()
                .setMetric(metricKey)
                .setMetricName(metricName(metricKey))
                .setCurrent(period(current, currentValue))
                .setPrevious(period(previous, previousValue))
                .setDelta(MetricValues.delta(currentValue, previousValue));
    }

    /* ================================================================== */
    /* 内部工具                                                            */
    /* ================================================================== */

    /**
     * 缺口分析的区间：固定档位由服务端按「今天」推算，
     * {@link OperationsGapRange#CUSTOM} 才读 {@code startDate} / {@code endDate}。
     *
     * @throws BusinessException 档位非法（400）
     */
    private DateRange resolveGapRange(OperationsGapQuery query) {
        String raw = query == null ? null : query.getRange();
        OperationsGapRange rangeType;
        if (raw == null || raw.isBlank()) {
            rangeType = OperationsGapRange.LAST_30D;
        } else {
            rangeType = OperationsGapRange.of(raw.trim());
            if (rangeType == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "缺口分析区间档位不合法：" + raw);
            }
        }
        LocalDate today = LocalDate.now();
        return switch (rangeType) {
            case LAST_7D -> new DateRange(today.minusDays(OperationsGapRange.LAST_7D.getDays() - 1L), today);
            case LAST_30D -> new DateRange(today.minusDays(OperationsGapRange.LAST_30D.getDays() - 1L), today);
            case CUSTOM -> (query == null ? new DateRangeQuery() : query).toRange();
        };
    }

    /**
     * 效果对比的「后一期」区间。
     *
     * <p>复用共享 DTO 的区间约定：默认最近 30 天、上限 366 天、闭区间；
     * 格式不合法返回 400，起止颠倒或超上限返回 9002。</p>
     */
    private DateRange currentRange(OperationsEffectQuery query) {
        DateRangeQuery rangeQuery = new DateRangeQuery();
        rangeQuery.setStartDate(query == null ? null : query.getFrom());
        rangeQuery.setEndDate(query == null ? null : query.getTo());
        return rangeQuery.toRange();
    }

    /**
     * 效果对比的「前一期」区间。
     *
     * <p>不传时自动取「与后一期<b>等长</b>、紧邻其前」的区间 ——
     * 两期长度不等时，差值里就混进了「区间长度不同」这个因素，
     * 那样的对比说明不了任何问题。</p>
     *
     * @throws BusinessException 只给了起止之一（400）/ 区间不合法（9002）
     */
    private DateRange previousRange(OperationsEffectQuery query, DateRange current) {
        String compareFrom = query == null ? null : trimToNull(query.getCompareFrom());
        String compareTo = query == null ? null : trimToNull(query.getCompareTo());
        if ((compareFrom == null) != (compareTo == null)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "对比区间的起止日期必须同时提供或同时省略");
        }
        if (compareFrom != null) {
            DateRangeQuery rangeQuery = new DateRangeQuery();
            rangeQuery.setStartDate(compareFrom);
            rangeQuery.setEndDate(compareTo);
            return rangeQuery.toRange();
        }
        LocalDate end = current.start().minusDays(1L);
        LocalDate start = end.minusDays(current.days() - 1L);
        return new DateRange(start, end);
    }

    /** 指标中文名：只从指标字典取，不另写一份名表 */
    private String metricName(String metricKey) {
        MetricDictionary.Metric metric = DICTIONARY.find(metricKey);
        return metric == null ? metricKey : metric.name();
    }

    /** 装配一期的取值 */
    private OperationsEffectVO.Period period(DateRange range, String value) {
        return new OperationsEffectVO.Period()
                .setFrom(range.start().toString())
                .setTo(range.end().toString())
                .setValue(value);
    }

    /** 桶的键：{@code 周内第几天-小时} */
    private static String bucketKey(int weekday, int hour) {
        return weekday + "-" + hour;
    }

    /**
     * 一个可服务时段覆盖了哪些「小时桶」。
     *
     * <p>判定规则：<b>整点落在时段内</b>（{@code start <= H:00 < end}）。
     * 于是 {@code 08:00–18:00} 覆盖 8–17 时共 10 个桶。</p>
     */
    private static List<Integer> hoursOf(MatchingCandidate.AvailableSlot slot) {
        List<Integer> hours = new ArrayList<>();
        for (int hour = 0; hour < GAP_HOURS; hour++) {
            LocalTime hourStart = LocalTime.of(hour, 0);
            if (!hourStart.isBefore(slot.start()) && hourStart.isBefore(slot.end())) {
                hours.add(hour);
            }
        }
        return hours;
    }

    /** 解析查询结果里的时间；解析不了返回 {@code null}（跳过该行，不让整张表挂掉） */
    private static LocalDateTime localDateTimeOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof java.util.Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), java.time.ZoneId.systemDefault());
        }
        String text = String.valueOf(value).trim();
        if (text.length() > 19) {
            text = text.substring(0, 19);
        }
        try {
            return LocalDateTime.parse(text.replace(' ', 'T'));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 解析查询结果里的时刻。
     *
     * <p>MySQL 的 {@code TIME} 列允许 {@code 24:00:00}（表示「当天结束」），
     * 而 {@code LocalTime} 表达不了 —— 这里折算成 {@code 23:59:59.999999999}，
     * 于是 23 点这一格仍然被算进「可服务」里。</p>
     */
    private static LocalTime localTimeOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalTime time) {
            return time;
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime();
        }
        String text = String.valueOf(value).trim();
        if ("24:00:00".equals(text) || "24:00".equals(text)) {
            return LocalTime.MAX;
        }
        try {
            return LocalTime.parse(text);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 取长整型值；{@code null} 返回 {@code null}（区别于「取不到就记 0」） */
    private static Long longOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取整型值；{@code null} 返回 {@code null} */
    private static Integer intOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 去空白；空白串视作 {@code null} */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 区间：默认最近 30 天、上限 366 天、闭区间；非法区间抛 9002 */
    private DateRange range(DateRangeQuery query) {
        return (query == null ? new DateRangeQuery() : query).toRange();
    }

    /** 订单聚合（含「按时完成」的窗口与「今日」边界） */
    private Map<String, Object> orderStats(DateRange range) {
        LocalDate today = LocalDate.now();
        return operationsMapper.selectOrderStats(
                range.startTime(), range.endTime(),
                operationsProperties.getFulfillmentWindowMinutes(),
                today.atStartOfDay(),
                // 用「次日零点」而不是「今日 23:59:59」：DATETIME(3) 精度下 23:59:59.500 会漏掉
                today.plusDays(1).atStartOfDay());
    }

    /** 趋势维度：缺省 ORDER；非法值抛 400 */
    private OperationsTrendDimension dimension(OperationsTrendQuery query) {
        String raw = query == null ? null : query.getDimension();
        if (raw == null || raw.isBlank()) {
            return OperationsTrendDimension.ORDER;
        }
        OperationsTrendDimension dimension = OperationsTrendDimension.of(raw.trim());
        if (dimension == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "趋势维度取值不合法：" + raw);
        }
        return dimension;
    }

    /** 趋势区间档位：缺省 LAST_30D；非法值抛 400 */
    private OperationsTrendRange trendRange(OperationsTrendQuery query) {
        String raw = query == null ? null : query.getRange();
        if (raw == null || raw.isBlank()) {
            return OperationsTrendRange.LAST_30D;
        }
        OperationsTrendRange range = OperationsTrendRange.of(raw.trim());
        if (range == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "趋势区间档位取值不合法：" + raw);
        }
        return range;
    }

    /**
     * 解析趋势区间。
     *
     * <p>固定档位由服务端按「今天」推算；{@link OperationsTrendRange#CUSTOM} 才读
     * {@code startDate} / {@code endDate}，并沿用共享区间的默认 30 天 / 上限 366 天规则。</p>
     */
    private DateRange resolveRange(OperationsTrendRange rangeType, OperationsTrendQuery query) {
        LocalDate today = LocalDate.now();
        DateRange range = switch (rangeType) {
            case TODAY -> new DateRange(today, today);
            case LAST_7D -> new DateRange(today.minusDays(OperationsTrendRange.LAST_7D.getDays() - 1L), today);
            case LAST_30D -> new DateRange(today.minusDays(OperationsTrendRange.LAST_30D.getDays() - 1L), today);
            case CUSTOM -> (query == null ? new DateRangeQuery() : query).toRange();
        };
        if (range.days() > operationsProperties.getTrendMaxDays()) {
            throw new BusinessException(ResultCode.STAT_RANGE_INVALID,
                    "趋势区间不能超过 " + operationsProperties.getTrendMaxDays() + " 天，请缩小范围");
        }
        return range;
    }

    /** 平均评分：两位小数字符串；无有效评价时为 {@code 0.00} */
    private String averageScore(Map<String, Object> reviewStats) {
        Object raw = reviewStats == null ? null : reviewStats.get("averageScore");
        if (raw == null) {
            return "0.00";
        }
        return decimalOf(raw).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 百分比数值（趋势图用）：分母为 0 时记 0 */
    private BigDecimal percentValue(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    /**
     * 计数值统一两位小数。
     *
     * <p>不统一的话，同一条序列里会出现 {@code 7} 与 {@code 0.00} 两种形态 ——
     * 前端拿到的是 JSON 数字还好，但导出与断言都会变得难以比较。</p>
     */
    private BigDecimal count(long value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    /** 从查询结果里取整数值，{@code null} 记 0 */
    private static long longOf(Map<String, Object> row, String key) {
        return longOf(row == null ? null : row.get(key));
    }

    private static long longOf(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    /** 从查询结果里取小数，{@code null} 记 0 */
    private static BigDecimal decimalOf(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    /** 解析 {@code yyyy-MM-dd}；解析不了返回 {@code null}（跳过该行，不让整条趋势挂掉） */
    private static LocalDate dateOf(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        if (text.length() > 10) {
            text = text.substring(0, 10);
        }
        try {
            return LocalDate.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
