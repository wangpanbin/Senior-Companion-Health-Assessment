package org.company.nianglin.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.config.MatchingProperties;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.constant.WorkStatus;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.mapper.MatchingMapper;
import org.company.nianglin.service.MatchingService;
import org.company.nianglin.service.support.matching.DimensionResult;
import org.company.nianglin.service.support.matching.DistanceMatchRule;
import org.company.nianglin.service.support.matching.LegacySlotParser;
import org.company.nianglin.service.support.matching.MatchTarget;
import org.company.nianglin.service.support.matching.MatchingCandidate;
import org.company.nianglin.service.support.matching.MatchingRule;
import org.company.nianglin.service.support.matching.MatchingScore;
import org.company.nianglin.service.support.matching.MatchingWeights;
import org.company.nianglin.service.support.matching.OrderMatchRank;
import org.company.nianglin.service.support.matching.RatingMatchRule;
import org.company.nianglin.service.support.matching.RegionMatchRule;
import org.company.nianglin.service.support.matching.SkillMatchRule;
import org.company.nianglin.service.support.matching.TimeMatchRule;
import org.company.nianglin.util.MaskUtil;
import org.company.nianglin.vo.CompanionMatchVO;
import org.company.nianglin.vo.OrderRecommendVO;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能调度的实现（V3.4 · FR-Service）。
 *
 * <h3>过滤链（顺序固定、逐级短路）</h3>
 *
 * <pre>
 * 1. 工作状态 = AVAILABLE
 * 2. 资质 = APPROVED
 * 3. 区域匹配（区域码 ∩；缺区域码时回退自由文本包含式）
 * 4. 时段可服务（订单就诊时间落在候选人的可服务时段内）
 * 5. 档期冲突（该时段已有在手订单）
 * </pre>
 *
 * <h3>「缺数据」与「不匹配」必须分开</h3>
 *
 * <p>第 3、4 步遇到<b>没有数据</b>的候选人（没填区域码、没登记时段）时，
 * 处置是「放行 + 降级记 0 + 写明原因」，而不是「淘汰」——
 * 淘汰一个只是没填资料的人，等于让「资料填得全」变成接单的前提，
 * 那不是匹配引擎该干的事。</p>
 *
 * <p>而<b>确实不匹配</b>（跨市、当天不上班、档期已占）才淘汰。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
@Slf4j
@Service
public class MatchingServiceImpl implements MatchingService {

    private final MatchingMapper matchingMapper;
    private final MatchingProperties matchingProperties;
    private final MatchingWeights weights;
    private final List<MatchingRule> rules;

    public MatchingServiceImpl(MatchingMapper matchingMapper, MatchingProperties matchingProperties) {
        this.matchingMapper = matchingMapper;
        this.matchingProperties = matchingProperties;
        this.weights = new MatchingWeights(
                matchingProperties.getRegionWeight(),
                matchingProperties.getTimeWeight(),
                matchingProperties.getDistanceWeight(),
                matchingProperties.getSkillWeight(),
                matchingProperties.getRatingWeight());
        // 规则是纯函数：配置由构造参数显式传入，不注入容器
        this.rules = List.of(
                new RegionMatchRule(),
                new TimeMatchRule(),
                new DistanceMatchRule(matchingProperties.getDistanceMaxMeters()),
                new SkillMatchRule(matchingProperties.getSkillMaxOrders()),
                new RatingMatchRule());
    }

    /* ================================================================== */
    /* 1 · 订单 → 候选陪诊员（家属端）                                       */
    /* ================================================================== */

    @Override
    public OrderRecommendVO recommendCompanions(CompanionOrder order, int limit) {
        OrderRecommendVO result = new OrderRecommendVO().setOrderId(order.getId()).setTotal(0);
        if (order == null) {
            return result;
        }

        List<Map<String, Object>> rows = matchingMapper.selectCandidates();
        if (rows.isEmpty()) {
            return result;
        }

        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Long id = longOf(row.get("userId"));
            if (id != null) {
                ids.add(id);
            }
        }

        Map<Long, List<MatchingCandidate.AvailableSlot>> slotsBy = loadSlots(ids);
        Map<Long, Map<String, Integer>> deptCountsBy = loadDepartmentCounts(ids);
        Map<Long, List<LocalDateTime>> inFlightBy = loadInFlightOrders(ids);

        MatchTarget target = toTarget(order);
        List<ScoredCandidate> scored = new ArrayList<>();

        for (Map<String, Object> row : rows) {
            Long companionId = longOf(row.get("userId"));
            if (companionId == null) {
                continue;
            }
            // ① 工作状态
            if (!WorkStatus.AVAILABLE.name().equals(text(row.get("workStatus")))) {
                continue;
            }
            // ② 资质
            if (!AuditStatus.APPROVED.name().equals(text(row.get("auditStatus")))) {
                continue;
            }

            MatchingCandidate candidate = buildCandidate(row, companionId,
                    slotsBy.getOrDefault(companionId, List.of()),
                    deptCountsBy.getOrDefault(companionId, Map.of()));

            // ③ 区域
            if (!regionPasses(candidate, target)) {
                continue;
            }
            // ④ 时段
            if (!slotPasses(candidate, target)) {
                continue;
            }
            // ⑤ 档期冲突
            if (hasConflict(companionId, target, inFlightBy)) {
                continue;
            }

            MatchingScore score = score(candidate, target);
            scored.add(new ScoredCandidate(companionId, text(row.get("realName")), score));
        }

        // 排序：总分降序 → 陪诊员 ID 升序（保证同分时顺序稳定）
        scored.sort(Comparator.comparingDouble((ScoredCandidate c) -> -c.score().total())
                .thenComparingLong(ScoredCandidate::companionId));

        int top = Math.max(0, limit);
        List<CompanionMatchVO> candidates = new ArrayList<>();
        for (ScoredCandidate item : scored) {
            if (candidates.size() >= top) {
                break;
            }
            candidates.add(toVO(item));
        }
        return result.setTotal(scored.size()).setCandidates(candidates);
    }

    /* ================================================================== */
    /* 2 · 陪诊员 → 订单打分（大厅排序）                                     */
    /* ================================================================== */

    @Override
    public List<OrderMatchRank> scoreOrders(CompanionProfile profile, List<CompanionOrder> orders) {
        List<OrderMatchRank> ranks = new ArrayList<>();
        if (profile == null || orders == null || orders.isEmpty()) {
            return ranks;
        }

        Long companionId = profile.getUserId();
        List<MatchingCandidate.AvailableSlot> slots =
                loadSlots(List.of(companionId)).getOrDefault(companionId, List.of());
        if (slots.isEmpty()) {
            // 结构化表没时段时回退解析自由文本（存量陪诊员的 available_time）
            slots = LegacySlotParser.parse(profile.getAvailableTime());
        }
        MatchingCandidate candidate = buildCandidate(profile, companionId, slots,
                loadDepartmentCounts(List.of(companionId)).getOrDefault(companionId, Map.of()));

        for (CompanionOrder order : orders) {
            MatchingScore score = score(candidate, toTarget(order));
            Map<String, Double> dimensions = new LinkedHashMap<>();
            for (String dimension : MatchingWeights.DIMENSIONS) {
                dimensions.put(dimension, score.dimensionScore(dimension));
            }
            ranks.add(new OrderMatchRank(order.getId(), score.total(), dimensions));
        }
        return ranks;
    }

    /* ================================================================== */
    /* 过滤链的三道「软/硬」判定                                            */
    /* ================================================================== */

    /**
     * 区域判定：确实跨市才淘汰，缺资料只降级。
     *
     * @return true = 保留
     */
    private boolean regionPasses(MatchingCandidate candidate, MatchTarget target) {
        if (candidate.hasRegionCode() && target.hasRegionCode()) {
            String candidateCode = candidate.regionCode().trim().toUpperCase();
            String targetCode = target.regionCode().trim().toUpperCase();
            // 同城（含同码）才留；跨市淘汰
            return RegionMatchRule.cityOf(candidateCode).equals(RegionMatchRule.cityOf(targetCode));
        }
        String serviceArea = candidate.serviceArea();
        String address = target.address();
        if (serviceArea != null && !serviceArea.isBlank()
                && address != null && address.contains(serviceArea.trim())) {
            return true;
        }
        // 候选人有区域码但订单没有 → 无法判定，按「不淘汰但降级」处理
        return true;
    }

    /**
     * 时段判定：明确登记了可服务时段、而订单落在时段外 → 淘汰；没登记 → 降级放行。
     *
     * @return true = 保留
     */
    private boolean slotPasses(MatchingCandidate candidate, MatchTarget target) {
        if (candidate.slots().isEmpty() || target.visitTime() == null) {
            return true;
        }
        int weekday = target.visitTime().getDayOfWeek().getValue();
        LocalTime time = target.visitTime().toLocalTime();
        for (MatchingCandidate.AvailableSlot slot : candidate.slots()) {
            if (slot.weekday() != weekday) {
                continue;
            }
            boolean inside = slot.start().isBefore(slot.end())
                    ? (!time.isBefore(slot.start()) && time.isBefore(slot.end()))
                    : (!time.isBefore(slot.start()) || time.isBefore(slot.end()));
            if (inside) {
                return true;
            }
        }
        return false;
    }

    /**
     * 档期冲突：候选人在目标时段已有在手订单 → 淘汰。
     *
     * @return true = 有冲突
     */
    private boolean hasConflict(Long companionId, MatchTarget target,
                                 Map<Long, List<LocalDateTime>> inFlightBy) {
        if (target.visitTime() == null) {
            return false;
        }
        List<LocalDateTime> inFlight = inFlightBy.getOrDefault(companionId, List.of());
        int window = matchingProperties.getConflictWindowMinutes();
        LocalDateTime targetStart = target.visitTime();
        LocalDateTime targetEnd = target.visitTime().plusMinutes(window);
        for (LocalDateTime start : inFlight) {
            LocalDateTime end = start.plusMinutes(window);
            boolean overlap = !targetEnd.isBefore(start) && !targetStart.isAfter(end);
            if (overlap) {
                return true;
            }
        }
        return false;
    }

    /* ================================================================== */
    /* 打分                                                                */
    /* ================================================================== */

    /** 跑一遍全部规则，产出可解释的得分 */
    private MatchingScore score(MatchingCandidate candidate, MatchTarget target) {
        Map<String, DimensionResult> results = new LinkedHashMap<>();
        for (MatchingRule rule : rules) {
            results.put(rule.dimension(), rule.evaluate(candidate, target));
        }
        return new MatchingScore(results, MatchingScore.weightedTotal(results, weights));
    }

    /* ================================================================== */
    /* 取数与快照组装                                                       */
    /* ================================================================== */

    private Map<Long, List<MatchingCandidate.AvailableSlot>> loadSlots(List<Long> ids) {
        Map<Long, List<MatchingCandidate.AvailableSlot>> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (Map<String, Object> row : matchingMapper.selectSlots(ids)) {
            Long companionId = longOf(row.get("companionId"));
            LocalTime start = timeOf(row.get("startTime"));
            LocalTime end = timeOf(row.get("endTime"));
            Integer weekday = intOf(row.get("weekday"));
            if (companionId == null || start == null || end == null || weekday == null) {
                continue;
            }
            map.computeIfAbsent(companionId, key -> new ArrayList<>())
                    .add(new MatchingCandidate.AvailableSlot(weekday, start, end));
        }
        return map;
    }

    private Map<Long, Map<String, Integer>> loadDepartmentCounts(List<Long> ids) {
        Map<Long, Map<String, Integer>> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (Map<String, Object> row : matchingMapper.selectDepartmentOrderCounts(ids)) {
            Long companionId = longOf(row.get("companionId"));
            String department = text(row.get("department"));
            Integer cnt = intOf(row.get("cnt"));
            if (companionId == null || department == null || cnt == null) {
                continue;
            }
            map.computeIfAbsent(companionId, key -> new HashMap<>()).put(department, cnt);
        }
        return map;
    }

    private Map<Long, List<LocalDateTime>> loadInFlightOrders(List<Long> ids) {
        Map<Long, List<LocalDateTime>> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (Map<String, Object> row : matchingMapper.selectInFlightOrders(ids)) {
            Long companionId = longOf(row.get("companionId"));
            LocalDateTime visitTime = dateTimeOf(row.get("visitTime"));
            if (companionId == null || visitTime == null) {
                continue;
            }
            map.computeIfAbsent(companionId, key -> new ArrayList<>()).add(visitTime);
        }
        return map;
    }

    /** 由查询行组装候选人快照 */
    private MatchingCandidate buildCandidate(Map<String, Object> row, Long companionId,
                                             List<MatchingCandidate.AvailableSlot> slots,
                                             Map<String, Integer> departmentCounts) {
        String regionCode = text(row.get("regionCode"));
        String serviceArea = text(row.get("serviceArea"));
        String availableTime = text(row.get("availableTime"));
        // 结构化表没时段时回退解析自由文本（存量陪诊员的 available_time）
        List<MatchingCandidate.AvailableSlot> effectiveSlots = slots;
        if (effectiveSlots.isEmpty()) {
            effectiveSlots = LegacySlotParser.parse(availableTime);
        }
        return new MatchingCandidate(
                companionId,
                regionCode,
                serviceArea,
                decimalOf(row.get("longitude")),
                decimalOf(row.get("latitude")),
                effectiveSlots,
                departmentCounts,
                decimalOf(row.get("score")));
    }

    /** 由陪诊员档案组装候选人快照（大厅方向） */
    private MatchingCandidate buildCandidate(CompanionProfile profile, Long companionId,
                                             List<MatchingCandidate.AvailableSlot> slots,
                                             Map<String, Integer> departmentCounts) {
        return new MatchingCandidate(
                companionId,
                profile.getServiceRegionCode(),
                profile.getServiceArea(),
                profile.getLongitude(),
                profile.getLatitude(),
                slots,
                departmentCounts,
                profile.getScore());
    }

    /** 订单快照 */
    private MatchTarget toTarget(CompanionOrder order) {
        return new MatchTarget(
                order.getId(),
                order.getServiceRegionCode(),
                order.getAddress(),
                order.getVisitTime(),
                order.getLongitude(),
                order.getLatitude(),
                order.getDepartment());
    }

    /* ================================================================== */
    /* 转换与工具                                                          */
    /* ================================================================== */

    private CompanionMatchVO toVO(ScoredCandidate item) {
        MatchingScore score = item.score();
        return new CompanionMatchVO()
                .setCompanionId(item.companionId())
                // 脱敏：只给家属看姓氏 + 姓氏 + 名字首字，绝不给完整姓名
                .setCompanionName(MaskUtil.name(item.realName()))
                .setTotalScore(score.totalPercent())
                .setRegionScore(score.dimensionPercent(MatchingWeights.DIMENSION_REGION))
                .setTimeScore(score.dimensionPercent(MatchingWeights.DIMENSION_TIME))
                .setDistanceScore(score.dimensionPercent(MatchingWeights.DIMENSION_DISTANCE))
                .setSkillScore(score.dimensionPercent(MatchingWeights.DIMENSION_SKILL))
                .setRatingScore(score.dimensionPercent(MatchingWeights.DIMENSION_RATING))
                .setReasons(score.reasons())
                .setNotes(score.notes());
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer intOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal decimalOf(Object value) {
        if (value == null) {
            return null;
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
            return null;
        }
    }

    private static LocalTime timeOf(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        try {
            return LocalTime.parse(text.length() > 8 ? text.substring(0, 8) : text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalDateTime dateTimeOf(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        String text = String.valueOf(value);
        try {
            return LocalDateTime.parse(text.replace(' ', 'T'));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 候选 + 得分（内部结构） */
    private record ScoredCandidate(Long companionId, String realName, MatchingScore score) {
    }
}
