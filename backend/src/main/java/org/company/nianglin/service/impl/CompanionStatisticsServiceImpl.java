package org.company.nianglin.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.DateRange;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.CompanionTrendDimension;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.PaymentStatus;
import org.company.nianglin.constant.StatisticsGranularity;
import org.company.nianglin.dto.CompanionTrendQuery;
import org.company.nianglin.dto.DateRangeQuery;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionStatisticsMapper;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.CompanionStatisticsService;
import org.company.nianglin.vo.ChartDataVO;
import org.company.nianglin.vo.CompanionExpertiseVO;
import org.company.nianglin.vo.CompanionIncomeVO;
import org.company.nianglin.vo.CompanionServiceProfileVO;
import org.company.nianglin.vo.CompanionTrendVO;
import org.company.nianglin.vo.CompanionWorkbenchOverviewVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 陪诊员工作台统计的实现（V3.3）。
 *
 * <h3>三件容易做错的事，这里都收住了</h3>
 *
 * <ol>
 *   <li><b>身份只从登录态取</b>：{@code SecurityUtils.currentUserId()}，
 *       全程不接收 {@code companionId} 入参 —— 越权矩阵里那条
 *       「带 ?companionId=999 也只能看到自己的数据」因此是天然成立的，
 *       而不是靠一条 if 挡住。</li>
 *   <li><b>口径分两套且字段名自带</b>：概览 / 收入是<b>区间</b>口径，
 *       画像是<b>累计</b>口径。字段名（{@code total*} 前缀）与指标字典的
 *       「统计范围」两处同时写明，不靠注释提醒。</li>
 *   <li><b>不重算平均评分</b>：读 {@code companion_profile.score}
 *       （评价提交时在事务内刷新，是维护得住的冗余字段）；
 *       但<b>累计完成单数不读</b> {@code companion_profile.order_count} ——
 *       那个字段运行期没人维护，只在建档时置 0。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since V3.3
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CompanionStatisticsServiceImpl implements CompanionStatisticsService {

    /** 擅长服务标签最多展示几个科室 */
    private static final int EXPERTISE_LIMIT = 5;

    /** 擅长服务标签的最小样本量：接过 1 单就自称「擅长」是噪声，不是标签 */
    private static final int EXPERTISE_MIN_SAMPLE = 5;

    private final CompanionStatisticsMapper companionStatisticsMapper;

    /* ================================================================== */
    /* 1 · 概览                                                            */
    /* ================================================================== */

    @Override
    public CompanionWorkbenchOverviewVO overview(DateRangeQuery query) {
        DateRange range = range(query);
        Long companionId = SecurityUtils.currentUserId();
        Map<String, Object> stats = companionStatisticsMapper.selectOrderStats(
                companionId, range.startTime(), range.endTime());

        long completed = longOf(stats, "completedCount");
        long cancelled = longOf(stats, "cancelledCount");

        return new CompanionWorkbenchOverviewVO()
                .setPeriodStart(range.start().toString())
                .setPeriodEnd(range.end().toString())
                .setCompletedOrderCount((int) completed)
                .setServiceDurationMinutes((int) longOf(stats, "serviceMinutes"))
                // 分母只算「已完成 + 已取消」：被拒的单从未进入履约，
                // 把它算进分母会让陪诊员凭空少一分完成率
                .setCompletionRate(ChartDataVO.percent(completed, completed + cancelled))
                .setAverageScore(averageScore(companionId))
                .setPeriodIncome(money(stats, "income"));
    }

    /* ================================================================== */
    /* 2 · 趋势                                                            */
    /* ================================================================== */

    @Override
    public CompanionTrendVO trend(CompanionTrendQuery query) {
        DateRange range = range(query);
        StatisticsGranularity granularity = granularity(query);
        CompanionTrendDimension dimension = dimension(query);
        Long companionId = SecurityUtils.currentUserId();

        // 先按区间逐日建桶（无数据的桶为 0），再用查询结果去累加。
        // 反过来做需要 diff 两次集合，而且一旦漏补就是静默的折线跳段。
        TreeMap<LocalDate, Bucket> buckets = emptyAxis(range, granularity);

        switch (dimension) {
            case ORDER -> fillOrderBuckets(buckets, granularity, companionId, range);
            case INCOME -> fillIncomeBuckets(buckets, granularity, companionId, range);
            case RATING -> fillRatingBuckets(buckets, granularity, companionId, range);
            default -> throw new BusinessException(ResultCode.PARAM_ERROR, "趋势维度取值不合法");
        }

        List<String> categories = new ArrayList<>(buckets.size());
        List<BigDecimal> data = new ArrayList<>(buckets.size());
        for (Map.Entry<LocalDate, Bucket> entry : buckets.entrySet()) {
            categories.add(entry.getKey().toString());
            data.add(entry.getValue().value(dimension));
        }

        CompanionTrendVO.Series series = new CompanionTrendVO.Series();
        series.setName(dimension.getLabel());
        series.setData(data);

        return new CompanionTrendVO()
                .setDimension(dimension.name())
                .setGranularity(granularity.name())
                .setPeriodStart(range.start().toString())
                .setPeriodEnd(range.end().toString())
                .setCategories(categories)
                .setSeries(List.of(series));
    }

    private void fillOrderBuckets(TreeMap<LocalDate, Bucket> buckets, StatisticsGranularity granularity,
                                  Long companionId, DateRange range) {
        for (Map<String, Object> row : companionStatisticsMapper.selectDailyOrderCounts(
                companionId, range.startTime(), range.endTime())) {
            LocalDate date = dateOf(row.get("d"));
            String status = row.get("status") == null ? null : String.valueOf(row.get("status"));
            if (date == null || status == null) {
                continue;
            }
            Bucket bucket = buckets.get(granularity.bucketDate(date));
            if (bucket == null) {
                continue;
            }
            if (OrderStatus.COMPLETED.name().equals(status) || OrderStatus.REVIEWED.name().equals(status)) {
                bucket.sum = bucket.sum.add(BigDecimal.valueOf(longOf(row.get("cnt"))));
            }
        }
    }

    private void fillIncomeBuckets(TreeMap<LocalDate, Bucket> buckets, StatisticsGranularity granularity,
                                   Long companionId, DateRange range) {
        for (Map<String, Object> row : companionStatisticsMapper.selectDailyIncome(
                companionId, range.startTime(), range.endTime())) {
            LocalDate date = dateOf(row.get("d"));
            if (date == null) {
                continue;
            }
            Bucket bucket = buckets.get(granularity.bucketDate(date));
            if (bucket == null) {
                continue;
            }
            bucket.sum = bucket.sum.add(decimalOf(row.get("income")));
        }
    }

    private void fillRatingBuckets(TreeMap<LocalDate, Bucket> buckets, StatisticsGranularity granularity,
                                    Long companionId, DateRange range) {
        for (Map<String, Object> row : companionStatisticsMapper.selectDailyRating(
                companionId, range.startTime(), range.endTime())) {
            LocalDate date = dateOf(row.get("d"));
            if (date == null) {
                continue;
            }
            Bucket bucket = buckets.get(granularity.bucketDate(date));
            if (bucket == null) {
                continue;
            }
            // 日均值不能直接平均成周均值（5 条 5 星 + 1 条 1 星 → 日均 3.0，
            // 真实周均 4.33）。所以按条数加权，权重就是当天的评价条数。
            long cnt = longOf(row.get("cnt"));
            bucket.sum = bucket.sum.add(decimalOf(row.get("avgScore")).multiply(BigDecimal.valueOf(cnt)));
            bucket.count += cnt;
        }
    }

    /* ================================================================== */
    /* 3 · 服务画像（累计口径）                                             */
    /* ================================================================== */

    @Override
    public CompanionServiceProfileVO profile() {
        Long companionId = SecurityUtils.currentUserId();
        Map<String, Object> stats = companionStatisticsMapper.selectLifetimeOrderStats(companionId);

        long totalCompleted = longOf(stats, "totalCompletedCount");
        long totalCancelled = longOf(stats, "totalCancelledCount");
        long accepted = longOf(stats, "acceptedCount");
        Integer onTimeRow = companionStatisticsMapper.selectOnTimeArrivalCount(companionId);
        int onTime = onTimeRow == null ? 0 : onTimeRow;

        return new CompanionServiceProfileVO()
                .setAverageScore(averageScore(companionId))
                .setTotalCompletedOrderCount((int) totalCompleted)
                .setTotalServedElderCount((int) longOf(stats, "totalServedElderCount"))
                // 没有 ARRIVE 打卡的订单视为不准时：没打卡本身就是履约问题，
                // 不能因为「没记录」反而算成准时
                .setPunctualityRate(ChartDataVO.percent(onTime, accepted))
                .setTotalCompletionRate(ChartDataVO.percent(totalCompleted, totalCompleted + totalCancelled))
                .setTotalServiceDurationMinutes((int) longOf(stats, "totalServiceMinutes"))
                .setExpertise(expertise(companionId));
    }

    /** 擅长服务标签：科室维度 TOP N，样本不足 5 单的不展示 */
    private List<CompanionExpertiseVO> expertise(Long companionId) {
        List<CompanionExpertiseVO> result = new ArrayList<>();
        for (Map<String, Object> row : companionStatisticsMapper.selectDepartmentStats(companionId)) {
            String department = row.get("department") == null ? null : String.valueOf(row.get("department"));
            long cnt = longOf(row.get("cnt"));
            if (department == null || department.isBlank() || cnt < EXPERTISE_MIN_SAMPLE) {
                continue;
            }
            CompanionExpertiseVO vo = new CompanionExpertiseVO();
            vo.setDepartment(department);
            vo.setOrderCount((int) cnt);
            result.add(vo);
            if (result.size() >= EXPERTISE_LIMIT) {
                break;
            }
        }
        return result;
    }

    /* ================================================================== */
    /* 4 · 收入中心（区间口径）                                             */
    /* ================================================================== */

    @Override
    public CompanionIncomeVO income(DateRangeQuery query) {
        DateRange range = range(query);
        Long companionId = SecurityUtils.currentUserId();

        Map<String, Object> stats = companionStatisticsMapper.selectOrderStats(
                companionId, range.startTime(), range.endTime());

        BigDecimal unsettled = BigDecimal.ZERO;
        BigDecimal settled = BigDecimal.ZERO;
        for (Map<String, Object> row : companionStatisticsMapper.selectSettlementStats(
                companionId, range.startTime(), range.endTime())) {
            String status = row.get("paymentStatus") == null ? null : String.valueOf(row.get("paymentStatus"));
            if (PaymentStatus.UNPAID.name().equals(status)) {
                unsettled = unsettled.add(decimalOf(row.get("amount")));
            } else if (PaymentStatus.SETTLED.name().equals(status)) {
                settled = settled.add(decimalOf(row.get("amount")));
            }
        }

        BigDecimal advance = BigDecimal.ZERO;
        BigDecimal service = BigDecimal.ZERO;
        for (Map<String, Object> row : companionStatisticsMapper.selectFeeItemTotals(
                companionId, range.startTime(), range.endTime())) {
            String itemType = row.get("itemType") == null ? null : String.valueOf(row.get("itemType"));
            if ("ADVANCE".equals(itemType)) {
                advance = advance.add(decimalOf(row.get("amount")));
            } else if ("SERVICE".equals(itemType)) {
                service = service.add(decimalOf(row.get("amount")));
            }
        }

        return new CompanionIncomeVO()
                .setPeriodStart(range.start().toString())
                .setPeriodEnd(range.end().toString())
                .setOrderAmount(money(stats, "orderAmount"))
                .setActualIncome(money(stats, "income"))
                .setUnsettledAmount(money(unsettled))
                .setSettledAmount(money(settled))
                .setAdvanceTotal(money(advance))
                .setServiceTotal(money(service));
    }

    /* ================================================================== */
    /* 内部工具                                                            */
    /* ================================================================== */

    /** 区间：默认最近 30 天、上限 366 天、闭区间；非法区间抛 9002 */
    private DateRange range(DateRangeQuery query) {
        return (query == null ? new DateRangeQuery() : query).toRange();
    }

    /** 粒度：缺省 DAY；非法值抛 400 */
    private StatisticsGranularity granularity(CompanionTrendQuery query) {
        String raw = query == null ? null : query.getGranularity();
        if (raw == null || raw.isBlank()) {
            return StatisticsGranularity.DAY;
        }
        StatisticsGranularity granularity = StatisticsGranularity.of(raw);
        if (granularity == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "时间粒度取值不合法：" + raw);
        }
        return granularity;
    }

    /** 趋势维度：缺省 ORDER；非法值抛 400 */
    private CompanionTrendDimension dimension(CompanionTrendQuery query) {
        String raw = query == null ? null : query.getDimension();
        if (raw == null || raw.isBlank()) {
            return CompanionTrendDimension.ORDER;
        }
        CompanionTrendDimension dimension = CompanionTrendDimension.of(raw);
        if (dimension == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "趋势维度取值不合法：" + raw);
        }
        return dimension;
    }

    /**
     * 构造完整的时间轴，所有桶初始为 0。
     *
     * <p>与 {@code StatisticsServiceImpl#emptyAxis} 同一条纪律：先按区间<b>逐日</b>建桶，
     * 再用查询结果累加。反过来做（先查有数据的日期、再补缺的）需要 diff 两次集合，
     * 而且一旦漏补就是静默的折线跳段。</p>
     */
    private TreeMap<LocalDate, Bucket> emptyAxis(DateRange range, StatisticsGranularity granularity) {
        TreeMap<LocalDate, Bucket> buckets = new TreeMap<>();
        for (LocalDate date = range.start(); !date.isAfter(range.end()); date = date.plusDays(1)) {
            buckets.computeIfAbsent(granularity.bucketDate(date), key -> new Bucket());
        }
        return buckets;
    }

    /**
     * 平均评分：读陪诊员档案里的冗余评分字段，<b>不重算</b>。
     *
     * <p>该字段在评价提交时于事务内刷新（{@code ReviewServiceImpl#refreshCompanionScore}），
     * 是维护得住的；重算一遍只会多一次全表扫描。</p>
     */
    private String averageScore(Long companionId) {
        BigDecimal score = companionStatisticsMapper.selectProfileScore(companionId);
        if (score == null) {
            return "0.00";
        }
        return score.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 金额统一两位小数字符串；{@code null} 记 0 */
    private String money(Map<String, Object> stats, String key) {
        return money(decimalOf(stats.get(key)));
    }

    /** 金额统一两位小数字符串 */
    private String money(BigDecimal value) {
        if (value == null) {
            return "0.00";
        }
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
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

    /** 从查询结果里取金额，{@code null} 记 0 */
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

    /**
     * 一个桶的累加器。
     *
     * <p>{@code sum} 的含义随维度而变：ORDER 是单数、INCOME 是金额、
     * RATING 是「日均分 × 条数」的加权和（{@code count} 是评价条数）。
     * 用一个类而不是数组，是因为这三个语义差别太大，数组下标迟早会看错。</p>
     */
    private static final class Bucket {

        /** 累加值 */
        private BigDecimal sum = BigDecimal.ZERO;

        /** 评价条数（仅 RATING 维度用到） */
        private long count;

        /** 该桶最终的值 */
        private BigDecimal value(CompanionTrendDimension dimension) {
            if (dimension == CompanionTrendDimension.RATING) {
                if (count <= 0) {
                    return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
                }
                return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
            }
            return sum.setScale(2, RoundingMode.HALF_UP);
        }
    }
}
