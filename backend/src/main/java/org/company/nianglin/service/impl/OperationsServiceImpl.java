package org.company.nianglin.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.DateRange;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.OperationsProperties;
import org.company.nianglin.constant.OperationsTrendDimension;
import org.company.nianglin.constant.OperationsTrendRange;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.dto.OperationsTrendQuery;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.OperationsMapper;
import org.company.nianglin.service.AdminService;
import org.company.nianglin.service.OperationsService;
import org.company.nianglin.vo.ChartDataVO;
import org.company.nianglin.vo.FulfillmentSummaryVO;
import org.company.nianglin.vo.OperationsExceptionItemVO;
import org.company.nianglin.vo.OperationsExceptionVO;
import org.company.nianglin.vo.OperationsHealthVO;
import org.company.nianglin.vo.OperationsOverviewVO;
import org.company.nianglin.vo.OperationsTrendVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private final OperationsMapper operationsMapper;

    /** 复用 V3.1 的履约风险判定结果（决策 5.1） */
    private final AdminService adminService;

    private final OperationsProperties operationsProperties;

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
    /* 内部工具                                                            */
    /* ================================================================== */

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
