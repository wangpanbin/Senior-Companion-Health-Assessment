package org.company.nianglin.service.impl;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.CompanionRankMetric;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.PaymentStatus;
import org.company.nianglin.constant.StatisticsGranularity;
import org.company.nianglin.dto.AdminOrderQuery;
import org.company.nianglin.dto.AdminUserQuery;
import org.company.nianglin.dto.StatisticsQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.ElderProfile;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.AdminReadMapper;
import org.company.nianglin.mapper.OrderReadMapper;
import org.company.nianglin.mapper.StatisticsMapper;
import org.company.nianglin.service.AdminService;
import org.company.nianglin.service.ExportService;
import org.company.nianglin.service.StatisticsService;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.util.MaskUtil;
import org.company.nianglin.vo.AdminUserVO;
import org.company.nianglin.vo.ChartDataVO;
import org.company.nianglin.vo.CompanionRankVO;
import org.company.nianglin.vo.MedicationMissedVO;
import org.company.nianglin.vo.OrderExportVO;
import org.company.nianglin.vo.OrderStatusStatVO;
import org.company.nianglin.vo.StatisticsOverviewVO;
import org.company.nianglin.vo.UserExportVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 数据统计与导出实现。
 *
 * @author 银龄伴诊团队
 * @since M10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatisticsServiceImpl implements StatisticsService {

    /** 默认统计区间长度：最近 30 天（含今天） */
    private static final int DEFAULT_RANGE_DAYS = 30;

    /** 区间上限：366 天（文档 §一），超过返回 9002 */
    private static final long MAX_RANGE_DAYS = 366;

    /** 排行默认与最大条数 */
    private static final int DEFAULT_RANK_LIMIT = 10;
    private static final int MAX_RANK_LIMIT = 50;

    /** 趋势图序列数：新增 / 完成 / 取消 */
    private static final int TREND_SERIES = 3;

    /** 漏服图序列数：任务总数 / 已服用 / 漏服 */
    private static final int MEDICATION_SERIES = 3;

    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StatisticsMapper statisticsMapper;
    private final AdminReadMapper adminReadMapper;
    private final OrderReadMapper orderReadMapper;
    private final AdminService adminService;
    private final ExportService exportService;
    private final UserNameResolver userNameResolver;

    /** 导出单次行数上限，防止误点导致 OOM（文档 §6 要求必须有上限） */
    @Value("${nianglin.export.max-rows:10000}")
    private int maxExportRows;

    /* ================================================================== */
    /* 1. 总览                                                             */
    /* ================================================================== */

    @Override
    public StatisticsOverviewVO overview(StatisticsQuery query) {
        DateRange range = range(query);

        /* ---------- 订单 ---------- */
        Map<String, Long> statusCounts = new HashMap<>();
        long orderTotal = 0L;
        for (Map<String, Object> row : statisticsMapper.selectOrderCountsByStatus(
                range.startTime(), range.endTime())) {
            String status = row.get("status") == null ? null : String.valueOf(row.get("status"));
            long count = longOf(row.get("cnt"));
            if (status == null) {
                continue;
            }
            statusCounts.merge(status, count, Long::sum);
            orderTotal += count;
        }
        long completed = countOf(statusCounts, OrderStatus.COMPLETED, OrderStatus.REVIEWED);
        long cancelled = countOf(statusCounts, OrderStatus.CANCELLED);
        long inProgress = countOf(statusCounts, OrderStatus.PENDING, OrderStatus.ACCEPTED,
                OrderStatus.IN_SERVICE);

        /* ---------- 用户 ---------- */
        long userTotal = longOf(statisticsMapper.selectUserTotalCount());
        long newUsers = longOf(statisticsMapper.selectNewUserCount(range.startTime(), range.endTime()));
        Map<String, Long> roleCounts = new HashMap<>();
        for (Map<String, Object> row : statisticsMapper.selectUserCountsByRole()) {
            Object role = row.get("role");
            if (role != null) {
                roleCounts.put(String.valueOf(role), longOf(row.get("cnt")));
            }
        }

        /* ---------- 陪诊员 ---------- */
        long activeCompanions = longOf(statisticsMapper.selectActiveCompanionCount(
                range.startTime(), range.endTime()));
        Long avgAccept = statisticsMapper.selectAvgAcceptMinutes(range.startTime(), range.endTime());

        /* ---------- 用药 ---------- */
        MedicationTotals medication = medicationTotals(range.startTime(), range.endTime(), null);

        StatisticsOverviewVO.Order order = new StatisticsOverviewVO.Order()
                .setTotalCount((int) orderTotal)
                .setCompletedCount((int) completed)
                .setCancelledCount((int) cancelled)
                .setInProgressCount((int) inProgress)
                .setCompletedRate(ChartDataVO.percent(completed, orderTotal))
                .setCancelRate(ChartDataVO.percent(cancelled, orderTotal));

        StatisticsOverviewVO.User user = new StatisticsOverviewVO.User()
                .setTotalCount((int) userTotal)
                .setNewCount((int) newUsers)
                // 分母用「区间开始前的用户数」= 总数 - 新增。
                // 用总数当分母会让增长率随基数膨胀而失真：新增 18 人，
                // 基数 213 与基数 18 是完全不同的两件事
                .setGrowthRate(ChartDataVO.percent(newUsers, Math.max(0L, userTotal - newUsers)))
                .setElderCount(intOf(roleCounts.get("ELDER")))
                .setFamilyCount(intOf(roleCounts.get("FAMILY")))
                .setCompanionCount(intOf(roleCounts.get("COMPANION")))
                .setAdminCount(intOf(roleCounts.get("ADMIN")));

        StatisticsOverviewVO.Companion companion = new StatisticsOverviewVO.Companion()
                .setActiveCount((int) activeCompanions)
                .setAvgAcceptMinutes(avgAccept == null ? 0 : avgAccept.intValue());

        StatisticsOverviewVO.Medication medicationVo = new StatisticsOverviewVO.Medication()
                .setTaskTotalCount((int) medication.total())
                .setTakenCount((int) medication.taken())
                .setMissedCount((int) medication.missed())
                .setMissedRate(ChartDataVO.percent(medication.missed(), medication.total()));

        return new StatisticsOverviewVO()
                .setStartDate(range.start())
                .setEndDate(range.end())
                .setOrder(order)
                .setUser(user)
                .setCompanion(companion)
                .setMedication(medicationVo);
    }

    /* ================================================================== */
    /* 2. 订单趋势                                                         */
    /* ================================================================== */

    @Override
    public ChartDataVO orderTrend(StatisticsQuery query) {
        DateRange range = range(query);
        StatisticsGranularity granularity = granularity(query);

        // TreeMap 保证分类轴按时间升序；键是桶的起始日
        TreeMap<LocalDate, long[]> buckets = emptyAxis(range, granularity, TREND_SERIES);

        for (Map<String, Object> row : statisticsMapper.selectDailyOrderCounts(
                range.startTime(), range.endTime())) {
            LocalDate date = dateOf(row.get("d"));
            String status = row.get("status") == null ? null : String.valueOf(row.get("status"));
            if (date == null || status == null) {
                continue;
            }
            long count = longOf(row.get("cnt"));
            long[] bucket = buckets.get(granularity.bucketDate(date));
            if (bucket == null) {
                // 区间外的数据（理论上不会出现，查询已限定区间）直接丢弃，
                // 绝不能让分类轴多出一个「区间外」的点
                continue;
            }
            // 第 0 条：新增（全部状态）；第 1 条：完成；第 2 条：取消
            bucket[0] += count;
            if (OrderStatus.COMPLETED.name().equals(status) || OrderStatus.REVIEWED.name().equals(status)) {
                bucket[1] += count;
            } else if (OrderStatus.CANCELLED.name().equals(status)) {
                bucket[2] += count;
            }
        }

        List<String> categories = new ArrayList<>(buckets.size());
        List<ChartDataVO.Series> series = List.of(
                new ChartDataVO.Series().setName("新增订单"),
                new ChartDataVO.Series().setName("完成订单"),
                new ChartDataVO.Series().setName("取消订单"));
        List<List<Integer>> data = new ArrayList<>();
        for (int i = 0; i < TREND_SERIES; i++) {
            data.add(new ArrayList<>(buckets.size()));
        }
        for (Map.Entry<LocalDate, long[]> entry : buckets.entrySet()) {
            categories.add(granularity.displayLabel(entry.getKey()));
            for (int i = 0; i < TREND_SERIES; i++) {
                data.get(i).add((int) entry.getValue()[i]);
            }
        }
        for (int i = 0; i < TREND_SERIES; i++) {
            series.get(i).setData(data.get(i));
        }

        return ChartDataVO.of(granularity.lowerName(), categories, series);
    }

    /* ================================================================== */
    /* 3. 订单状态分布                                                     */
    /* ================================================================== */

    @Override
    public List<OrderStatusStatVO> orderStatus(StatisticsQuery query) {
        DateRange range = range(query);

        Map<String, Long> counts = new HashMap<>();
        long total = 0L;
        for (Map<String, Object> row : statisticsMapper.selectOrderCountsByStatus(
                range.startTime(), range.endTime())) {
            Object status = row.get("status");
            if (status == null) {
                continue;
            }
            long count = longOf(row.get("cnt"));
            counts.merge(String.valueOf(status), count, Long::sum);
            total += count;
        }

        // 六种状态全部返回：饼图图例按数据项渲染，缺状态会让图例少一块颜色，
        // 看起来像是系统丢了状态定义（文档 §3 实现要点）
        List<OrderStatusStatVO> result = new ArrayList<>(OrderStatus.values().length);
        for (OrderStatus status : OrderStatus.values()) {
            result.add(OrderStatusStatVO.of(status, counts.getOrDefault(status.name(), 0L), total));
        }
        return result;
    }

    /* ================================================================== */
    /* 4. 陪诊员排行                                                       */
    /* ================================================================== */

    @Override
    public List<CompanionRankVO> companionRank(StatisticsQuery query) {
        DateRange range = range(query);
        CompanionRankMetric metric = rankMetric(query);
        int limit = rankLimit(query);

        List<Map<String, Object>> rows = statisticsMapper.selectCompanionRankRows(
                range.startTime(), range.endTime());

        record Row(Long companionId, long orderCount, long completedCount, BigDecimal score) {
        }
        List<Row> candidates = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Object companionId = row.get("companionId");
            if (companionId == null) {
                continue;
            }
            long orderCount = longOf(row.get("orderCount"));
            if (orderCount <= 0) {
                // 接单数为 0 的不进榜（文档 §4 实现要点第 2 条）
                continue;
            }
            candidates.add(new Row(((Number) companionId).longValue(), orderCount,
                    longOf(row.get("completedCount")), decimalOf(row.get("score"))));
        }

        // 默认按接单数降序；评分相同（或按评分排序时同分）再按接单数降序，
        // 让排序结果稳定 —— 否则同一批数据两次请求的名次可能不一样
        candidates.sort(metric == CompanionRankMetric.SCORE
                ? java.util.Comparator.comparing(Row::score,
                        java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                        .reversed()
                        .thenComparing(Row::orderCount, java.util.Comparator.reverseOrder())
                : java.util.Comparator.comparingLong(Row::orderCount).reversed()
                        .thenComparing(Row::completedCount, java.util.Comparator.reverseOrder()));

        if (candidates.size() > limit) {
            candidates = candidates.subList(0, limit);
        }

        Set<Long> ids = new LinkedHashSet<>();
        candidates.forEach(row -> ids.add(row.companionId()));
        Map<Long, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Map<String, Object> row : statisticsMapper.selectCompanionNames(ids)) {
                Object userId = row.get("userId");
                Object realName = row.get("realName");
                if (userId != null) {
                    names.put(((Number) userId).longValue(), realName == null ? null : String.valueOf(realName));
                }
            }
        }

        List<CompanionRankVO> result = new ArrayList<>(candidates.size());
        for (int i = 0; i < candidates.size(); i++) {
            Row row = candidates.get(i);
            result.add(CompanionRankVO.of(i + 1, row.companionId(),
                    names.get(row.companionId()), row.orderCount(), row.completedCount(), row.score()));
        }
        return result;
    }

    /* ================================================================== */
    /* 5. 漏服率                                                           */
    /* ================================================================== */

    @Override
    public MedicationMissedVO medicationMissed(StatisticsQuery query) {
        DateRange range = range(query);
        StatisticsGranularity granularity = granularity(query);

        TreeMap<LocalDate, long[]> buckets = emptyAxis(range, granularity, MEDICATION_SERIES);
        long total = 0L;
        long taken = 0L;
        long missed = 0L;

        for (Map<String, Object> row : statisticsMapper.selectDailyMedicationCounts(
                range.startTime(), range.endTime(), query.getElderId())) {
            LocalDate date = dateOf(row.get("d"));
            if (date == null) {
                continue;
            }
            long[] bucket = buckets.get(granularity.bucketDate(date));
            long dayTotal = longOf(row.get("total"));
            long dayTaken = longOf(row.get("taken"));
            long dayMissed = longOf(row.get("missed"));
            total += dayTotal;
            taken += dayTaken;
            missed += dayMissed;
            if (bucket == null) {
                continue;
            }
            bucket[0] += dayTotal;
            bucket[1] += dayTaken;
            // 用 was_missed 的口径，所以「漏了之后补记」的任务在这里仍然计入漏服
            bucket[2] += dayMissed;
        }

        List<String> categories = new ArrayList<>(buckets.size());
        List<ChartDataVO.Series> series = List.of(
                new ChartDataVO.Series().setName("任务总数"),
                new ChartDataVO.Series().setName("已服用"),
                new ChartDataVO.Series().setName("漏服"));
        List<List<Integer>> data = new ArrayList<>();
        for (int i = 0; i < MEDICATION_SERIES; i++) {
            data.add(new ArrayList<>(buckets.size()));
        }
        for (Map.Entry<LocalDate, long[]> entry : buckets.entrySet()) {
            categories.add(granularity.displayLabel(entry.getKey()));
            for (int i = 0; i < MEDICATION_SERIES; i++) {
                data.get(i).add((int) entry.getValue()[i]);
            }
        }
        for (int i = 0; i < MEDICATION_SERIES; i++) {
            series.get(i).setData(data.get(i));
        }

        return MedicationMissedVO.of(ChartDataVO.of(granularity.lowerName(), categories, series),
                total, taken, missed);
    }

    /* ================================================================== */
    /* 6 / 7. 导出                                                         */
    /* ================================================================== */

    @Override
    public void exportOrders(AdminOrderQuery query, HttpServletResponse response) {
        List<CompanionOrder> orders = adminService.queryOrdersForExport(query, maxExportRows);
        if (orders.size() > maxExportRows) {
            throw new BusinessException(ResultCode.EXPORT_LIMIT_EXCEEDED,
                    "本次筛选结果超过 " + maxExportRows + " 行，请缩小筛选范围后再导出");
        }

        List<OrderExportVO> rows = toOrderExportRows(orders);
        exportService.writeExcel(response, "订单数据", OrderExportVO.class, rows, "订单数据");
    }

    @Override
    public void exportUsers(AdminUserQuery query, HttpServletResponse response) {
        List<SysUser> users = adminService.queryUsersForExport(query, maxExportRows);
        if (users.size() > maxExportRows) {
            throw new BusinessException(ResultCode.EXPORT_LIMIT_EXCEEDED,
                    "本次筛选结果超过 " + maxExportRows + " 行，请缩小筛选范围后再导出");
        }

        Map<Long, Integer> orderCounts = new HashMap<>();
        List<Long> userIds = users.stream().map(SysUser::getId).toList();
        if (!userIds.isEmpty()) {
            for (Map<String, Object> row : adminReadMapper.selectOrderCountsByUserIds(userIds)) {
                Object uid = row.get("uid");
                if (uid != null) {
                    orderCounts.put(((Number) uid).longValue(), (int) longOf(row.get("cnt")));
                }
            }
        }

        List<UserExportVO> rows = new ArrayList<>(users.size());
        for (SysUser user : users) {
            // 复用 AdminUserVO 的脱敏与中文映射，再摊平成导出列。
            // 单独写一遍脱敏是这个模块最容易出的合规事故（导出文件会流出平台之外）
            AdminUserVO vo = AdminUserVO.of(user, orderCounts.get(user.getId()));
            UserExportVO export = new UserExportVO();
            export.setId(vo.getId());
            export.setUsername(vo.getUsername());
            export.setNickname(vo.getNickname());
            export.setPhone(vo.getPhone());
            export.setRoleLabel(vo.getRoleLabel());
            export.setStatusLabel(vo.getStatusLabel());
            export.setOrderCount(vo.getOrderCount());
            export.setCreateTime(format(vo.getCreateTime()));
            export.setLastLoginTime(format(vo.getLastLoginTime()));
            rows.add(export);
        }

        exportService.writeExcel(response, "用户数据", UserExportVO.class, rows, "用户数据");
    }

    /**
     * 订单实体 → 导出行。
     *
     * <p>三次批量查询覆盖全部行（老人 / 家属与陪诊员姓名），
     * 不做逐条查询：1 万行逐条查会产生 3 万次往返。</p>
     */
    private List<OrderExportVO> toOrderExportRows(List<CompanionOrder> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }

        Set<Long> elderIds = new LinkedHashSet<>();
        Set<Long> userIds = new LinkedHashSet<>();
        for (CompanionOrder order : orders) {
            if (order.getElderId() != null) {
                elderIds.add(order.getElderId());
            }
            if (order.getFamilyId() != null) {
                userIds.add(order.getFamilyId());
            }
            if (order.getCompanionId() != null) {
                userIds.add(order.getCompanionId());
            }
        }

        Map<Long, ElderProfile> elders = new HashMap<>();
        if (!elderIds.isEmpty()) {
            for (ElderProfile elder : orderReadMapper.selectEldersIgnoringLogicDelete(elderIds)) {
                elders.put(elder.getId(), elder);
            }
        }
        Map<Long, String> names = userNameResolver.resolveAll(userIds);

        List<OrderExportVO> rows = new ArrayList<>(orders.size());
        for (CompanionOrder order : orders) {
            ElderProfile elder = elders.get(order.getElderId());
            OrderExportVO row = new OrderExportVO();
            row.setOrderNo(order.getOrderNo());
            row.setElderName(MaskUtil.name(elder == null ? null : elder.getName()));
            row.setElderAge(ageOf(elder));
            row.setFamilyName(MaskUtil.name(names.get(order.getFamilyId())));
            row.setCompanionName(MaskUtil.name(names.get(order.getCompanionId())));
            row.setHospital(order.getHospital());
            row.setDepartment(order.getDepartment());
            row.setVisitTime(format(order.getVisitTime()));
            row.setStatusLabel(OrderStatus.labelOf(order.getStatus()));
            row.setFee(order.getFee() == null
                    ? null : order.getFee().setScale(2, RoundingMode.HALF_UP).toPlainString());
            row.setPaymentStatusLabel(PaymentStatus.labelOf(order.getPaymentStatus()));
            row.setCreateTime(format(order.getCreateTime()));
            row.setAcceptTime(format(order.getAcceptTime()));
            row.setFinishTime(format(order.getFinishTime()));
            rows.add(row);
        }
        return rows;
    }

    /* ================================================================== */
    /* 内部：区间与粒度                                                    */
    /* ================================================================== */

    /** 统计区间（闭区间） */
    private record DateRange(LocalDate start, LocalDate end) {

        LocalDateTime startTime() {
            return start.atStartOfDay();
        }

        /** 结束日折算成当天最后一秒，保证「含当天」 */
        LocalDateTime endTime() {
            return end.atTime(23, 59, 59);
        }

        long days() {
            return ChronoUnit.DAYS.between(start, end) + 1;
        }
    }

    /**
     * 解析统计区间。
     *
     * <p>三个容易错的地方都在这里收口：默认最近 30 天、起止颠倒、超过 366 天。
     * 把它们散到每个接口里各写一遍，迟早在某一个接口上漏掉 ——
     * 而「区间反了」在 SQL 层不会报错，只会静默返回空数据。</p>
     */
    private DateRange range(StatisticsQuery query) {
        LocalDate today = LocalDate.now();
        LocalDate end = query == null ? null : parseDate(query.getEndDate(), "结束日期");
        LocalDate start = query == null ? null : parseDate(query.getStartDate(), "开始日期");
        if (end == null) {
            end = today;
        }
        if (start == null) {
            start = end.minusDays(DEFAULT_RANGE_DAYS - 1L);
        }
        if (start.isAfter(end)) {
            throw new BusinessException(ResultCode.STAT_RANGE_INVALID, "开始日期不能晚于结束日期");
        }
        DateRange range = new DateRange(start, end);
        if (range.days() > MAX_RANGE_DAYS) {
            throw new BusinessException(ResultCode.STAT_RANGE_INVALID,
                    "统计区间不能超过 " + MAX_RANGE_DAYS + " 天，请缩小范围");
        }
        return range;
    }

    private StatisticsGranularity granularity(StatisticsQuery query) {
        if (query == null || query.getGranularity() == null || query.getGranularity().isBlank()) {
            return StatisticsGranularity.DAY;
        }
        StatisticsGranularity granularity = StatisticsGranularity.of(query.getGranularity());
        if (granularity == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "时间粒度取值不合法：" + query.getGranularity());
        }
        return granularity;
    }

    private CompanionRankMetric rankMetric(StatisticsQuery query) {
        if (query == null || query.getMetric() == null || query.getMetric().isBlank()) {
            return CompanionRankMetric.ORDER_COUNT;
        }
        CompanionRankMetric metric = CompanionRankMetric.of(query.getMetric());
        if (metric == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "排行指标取值不合法：" + query.getMetric());
        }
        return metric;
    }

    private int rankLimit(StatisticsQuery query) {
        Integer limit = query == null ? null : query.getLimit();
        if (limit == null || limit < 1) {
            return DEFAULT_RANK_LIMIT;
        }
        return Math.min(limit, MAX_RANK_LIMIT);
    }

    /**
     * 构造完整的时间轴，所有桶初始为 0。
     *
     * <p>这是「无数据日期补 0」的落点：先按区间<b>逐日</b>建桶，
     * 再用查询结果去累加。反过来做（先查出有数据的日期、再补缺的）需要 diff
     * 两次集合，而且一旦漏补就是静默的折线跳段。</p>
     */
    private TreeMap<LocalDate, long[]> emptyAxis(DateRange range, StatisticsGranularity granularity,
                                                 int seriesCount) {
        TreeMap<LocalDate, long[]> buckets = new TreeMap<>();
        for (LocalDate date = range.start(); !date.isAfter(range.end()); date = date.plusDays(1)) {
            buckets.computeIfAbsent(granularity.bucketDate(date),
                    key -> new long[seriesCount]);
        }
        return buckets;
    }

    /* ================================================================== */
    /* 内部：小工具                                                        */
    /* ================================================================== */

    private MedicationTotals medicationTotals(LocalDateTime start, LocalDateTime end, Long elderId) {
        long total = 0L;
        long taken = 0L;
        long missed = 0L;
        for (Map<String, Object> row : statisticsMapper.selectDailyMedicationCounts(start, end, elderId)) {
            total += longOf(row.get("total"));
            taken += longOf(row.get("taken"));
            missed += longOf(row.get("missed"));
        }
        return new MedicationTotals(total, taken, missed);
    }

    private record MedicationTotals(long total, long taken, long missed) {
    }

    private static long countOf(Map<String, Long> counts, OrderStatus... statuses) {
        long sum = 0L;
        for (OrderStatus status : statuses) {
            sum += counts.getOrDefault(status.name(), 0L);
        }
        return sum;
    }

    private static long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * {@code Long} → {@code int}。
     *
     * <p>不能直接写 {@code (int) longValue}（Java 不允许 Long 直接窄化为 int），
     * 且统计口径下即便真出现超 {@link Integer#MAX_VALUE} 的脏数据，
     * 也应该夹逼而不是让它回绕成负数 —— 回绕会让「完成率」显示成 -37%。</p>
     */
    private static int intOf(Long value) {
        if (value == null) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : value.intValue();
    }

    private static BigDecimal decimalOf(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return null;
    }

    /** 解析 {@code yyyy-MM-dd} / 数据库返回的日期串；解析不了返回 {@code null}（按缺数据处理） */
    private static LocalDate dateOf(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            // 兼容 DATE_FORMAT 可能带上的时间部分
            return text.length() > 10 ? LocalDateTime.parse(text.replace(' ', 'T')).toLocalDate()
                    : LocalDate.parse(text);
        } catch (Exception e) {
            return null;
        }
    }

    private static LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, field + " 格式应为 yyyy-MM-dd");
        }
    }

    private static String format(LocalDateTime time) {
        return time == null ? null : time.format(DATETIME);
    }

    private static Integer ageOf(ElderProfile elder) {
        if (elder == null || elder.getBirthDate() == null) {
            return null;
        }
        return Period.between(elder.getBirthDate(), LocalDate.now()).getYears();
    }
}
