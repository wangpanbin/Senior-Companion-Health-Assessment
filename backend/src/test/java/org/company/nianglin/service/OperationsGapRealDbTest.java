package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.company.nianglin.dto.OperationsGapQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.mapper.CompanionOrderMapper;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.service.support.matching.LegacySlotParser;
import org.company.nianglin.service.support.matching.MatchingCandidate;
import org.company.nianglin.vo.OperationsGapVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 供给时段缺口分析的<b>真库</b>一致性测试（V3.6 · T4 · T7 · 真库层）。
 *
 * <h3>为什么单测之外还要真库这一层</h3>
 *
 * <p>单测锁的是「Service 拿到这些行之后怎么分桶」，
 * 而「哪些订单算待接未接单」「哪些陪诊员算有可服务时段」这两件事，
 * 只有真跑一遍数据库才知道。</p>
 *
 * <p>所以这里的断言方式是：<b>用实体查询（MyBatis-Plus）把原始数据捞出来，
 * 在 Java 里逐条重算一遍，再和接口返回的桶逐个比对</b>。
 * 两条取数路径（原生 {@code @Select} vs 实体查询）互为对照，任何一边漂移都会对不上。</p>
 *
 * <h3>区间为什么不用默认的最近 30 天</h3>
 *
 * <p>种子数据的下单时间落在 2026-08-18 ~ 2026-09-18，而「今天」是 2026-10-20 ——
 * 默认区间里一条待接单都没有，测出来的全是 0，断言等于没写。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
@SpringBootTest
@DisplayName("V3.6 真库一致性：缺口表与逐条 Java 统计必须一致")
class OperationsGapRealDbTest {

    /** 覆盖全部种子订单的区间 */
    private static final String START_DATE = "2026-08-01";
    private static final String END_DATE = "2026-09-30";

    @Autowired
    private OperationsService service;

    @Autowired
    private CompanionOrderMapper orderMapper;

    @Autowired
    private CompanionProfileMapper profileMapper;

    /* ================================================================== */
    /* 1 · 需求侧：待接未接单的分桶                                       */
    /* ================================================================== */

    @Test
    @DisplayName("需求侧 · 每个桶的待接未接单数与逐条统计一致")
    void pendingCountsShouldMatchRowByRowAggregation() {
        OperationsGapVO vo = service.gap(query());

        Map<String, Integer> expected = new HashMap<>();
        for (CompanionOrder order : orderMapper.selectList(Wrappers.<CompanionOrder>lambdaQuery()
                .eq(CompanionOrder::getStatus, "PENDING")
                .between(CompanionOrder::getVisitTime, startTime(), endTime()))) {
            LocalDateTime visitTime = order.getVisitTime();
            if (visitTime == null) {
                continue;
            }
            String key = visitTime.getDayOfWeek().getValue() + "-" + visitTime.getHour();
            expected.merge(key, 1, Integer::sum);
        }

        for (OperationsGapVO.Item item : vo.getItems()) {
            String key = item.getWeekday() + "-" + item.getHour();
            assertEquals(expected.getOrDefault(key, 0), item.getPendingUnaccepted(),
                    "桶 " + key + " 的待接未接单数与逐条统计不一致");
        }
    }

    /* ================================================================== */
    /* 2 · 供给侧：可服务时段的回退解析                                   */
    /* ================================================================== */

    @Test
    @DisplayName("供给侧 · 每个桶的可服务陪诊员数与逐条解析一致（同一人每桶只计 1 次）")
    void availableCompanionsShouldMatchRowByRowParsing() {
        OperationsGapVO vo = service.gap(query());

        Map<String, Set<Long>> expected = new HashMap<>();
        int withoutSlots = 0;
        for (CompanionProfile profile : eligibleProfiles()) {
            List<MatchingCandidate.AvailableSlot> slots = LegacySlotParser.parse(profile.getAvailableTime());
            if (slots.isEmpty()) {
                withoutSlots++;
                continue;
            }
            for (MatchingCandidate.AvailableSlot slot : slots) {
                for (int hour : hoursOf(slot)) {
                    expected.computeIfAbsent(slot.weekday() + "-" + hour, key -> new HashSet<>())
                            .add(profile.getUserId());
                }
            }
        }

        for (OperationsGapVO.Item item : vo.getItems()) {
            String key = item.getWeekday() + "-" + item.getHour();
            assertEquals(expected.getOrDefault(key, Set.of()).size(), item.getAvailableCompanions(),
                    "桶 " + key + " 的可服务陪诊员数与逐条解析不一致");
        }
        assertEquals(withoutSlots, vo.getCompanionsWithoutSlots(),
                "解析不出可服务时段的陪诊员数与逐条统计不一致");
    }

    /**
     * 供给只算「有资格接单」的陪诊员。
     *
     * <p>没通过资质审核的、休息中 / 离线的陪诊员<b>接不了单</b>。
     * 把他们算作供给会让缺口被低估 —— 平台会以为「这个时段有人」，
     * 而实际上没人会接，那正是这个指标要回答的问题。</p>
     */
    @Test
    @DisplayName("供给侧 · 资质未通过 / 休息 / 离线的陪诊员不计入供给")
    void ineligibleCompanionsShouldNotCountAsSupply() {
        // 库里确实存在「有可服务时段但没资格接单」的陪诊员（否则这个用例测不出东西）
        List<CompanionProfile> ineligible = profileMapper.selectList(
                Wrappers.<CompanionProfile>lambdaQuery()
                        .ne(CompanionProfile::getAuditStatus, "APPROVED")
                        .or()
                        .ne(CompanionProfile::getWorkStatus, "AVAILABLE"));
        List<CompanionProfile> withSlots = ineligible.stream()
                .filter(p -> !LegacySlotParser.parse(p.getAvailableTime()).isEmpty())
                .toList();
        assertTrue(!withSlots.isEmpty(),
                "前提失效：库里没有「有可服务时段但没资格接单」的陪诊员，本用例失去意义");

        OperationsGapVO vo = service.gap(query());
        long maxSupply = vo.getItems().stream()
                .mapToLong(OperationsGapVO.Item::getAvailableCompanions)
                .max().orElse(0L);
        long eligibleCount = eligibleProfiles().size();
        assertTrue(maxSupply <= eligibleCount,
                "任一桶的可服务陪诊员数不应超过「有资格接单」的陪诊员总数（" + maxSupply
                        + " > " + eligibleCount + "）");
    }

    /* ================================================================== */
    /* 3 · 缺口符号与桶形状                                               */
    /* ================================================================== */

    @Test
    @DisplayName("缺口 = 需求 − 供给（逐桶核对），且桶恒为 168 个")
    void gapShouldBeDemandMinusSupplyInEveryBucket() {
        OperationsGapVO vo = service.gap(query());

        assertEquals(168, vo.getTotal());
        assertEquals(168, vo.getItems().size());
        for (OperationsGapVO.Item item : vo.getItems()) {
            assertEquals(item.getPendingUnaccepted() - item.getAvailableCompanions(), item.getGap(),
                    "桶 " + item.getWeekday() + "-" + item.getHour() + " 的缺口符号或数值不对");
        }
    }

    @Test
    @DisplayName("真库上确实存在缺口（否则这个测试等于没测）")
    void seedDataShouldActuallyContainPendingOrders() {
        long pending = orderMapper.selectCount(Wrappers.<CompanionOrder>lambdaQuery()
                .eq(CompanionOrder::getStatus, "PENDING")
                .between(CompanionOrder::getVisitTime, startTime(), endTime()));

        assertTrue(pending > 0, "种子数据里没有待接单 —— 缺口分析测不出东西，请检查种子数据区间");
    }

    /* ================================================================== */
    /* 工具                                                                */
    /* ================================================================== */

    /**
     * 有资格接单的陪诊员：资质 APPROVED **且**工作状态 AVAILABLE。
     *
     * <p>与 {@code OperationsMapper#selectCompanionServiceSlots()} /
     * {@code #selectCompanionAvailableTimes()} 的过滤条件**逐字一致** ——
     * 一边漏了过滤，供给就会被高估。</p>
     */
    private List<CompanionProfile> eligibleProfiles() {
        return profileMapper.selectList(Wrappers.<CompanionProfile>lambdaQuery()
                .eq(CompanionProfile::getAuditStatus, "APPROVED")
                .eq(CompanionProfile::getWorkStatus, "AVAILABLE"));
    }

    /** 整点落在时段内的那些小时（与服务侧同一套规则） */
    private static List<Integer> hoursOf(MatchingCandidate.AvailableSlot slot) {
        List<Integer> hours = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            LocalTime hourStart = LocalTime.of(hour, 0);
            if (!hourStart.isBefore(slot.start()) && hourStart.isBefore(slot.end())) {
                hours.add(hour);
            }
        }
        return hours;
    }

    private static OperationsGapQuery query() {
        OperationsGapQuery query = new OperationsGapQuery();
        query.setRange("CUSTOM");
        query.setStartDate(START_DATE);
        query.setEndDate(END_DATE);
        return query;
    }

    private static LocalDateTime startTime() {
        return LocalDate.parse(START_DATE).atStartOfDay();
    }

    private static LocalDateTime endTime() {
        return LocalDate.parse(END_DATE).atTime(23, 59, 59);
    }
}
