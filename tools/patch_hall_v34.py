# -*- coding: utf-8 -*-
"""把 OrderServiceImpl 的大厅排序从 V3.1 的 SQL 三级键换成 V3.4 的五维加权。

用法：python tools/patch_hall_v34.py
"""
import io
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "backend/src/main/java/org/company/nianglin/service/impl/OrderServiceImpl.java"

OLD_BLOCK_START = "    /**\n     * 大厅排序键白名单：排序键 → 排序列"
OLD_BLOCK_END = "    /* ================================================================== */\n    /* 4. 订单详情                                                         */"

NEW_BLOCK = '''    /**
     * 大厅排序键白名单（V3.4）：排序键 → 排序维度。
     *
     * <p>V3.1 只开放 {@code RECOMMEND}（SQL 三级键），并把距离 / 时间匹配 / 收益优先
     * 三个维度留给 V3.4 —— 理由是当时 {@code service_area} / {@code available_time}
     * 还是自由文本，写死四种排序必然被推翻。</p>
     *
     * <p>V3.4 换成真正的五维加权引擎，开放 {@code DISTANCE} / {@code TIME_MATCH}；
     * {@code INCOME} <b>仍不开放</b> —— 没有真实结算金额口径（roadmap 决策 D8）。</p>
     *
     * <p>用映射表而不是 {@code if} / {@code switch} 分支：加新维度时，
     * 「白名单放行」与「排序实现」仍然是同一处改动，不会出现
     * 「放行了却没实现」这种半吊子状态。</p>
     */
    private static final Map<String, String> HALL_SORTS = Map.of(
            "RECOMMEND", SORT_TOTAL,
            "DISTANCE", MatchingWeights.DIMENSION_DISTANCE,
            "TIME_MATCH", MatchingWeights.DIMENSION_TIME);

    /** 「按加权总分排」的伪维度名（总分不是五维之一） */
    private static final String SORT_TOTAL = "TOTAL";

    @Override
    public PageResult<OrderVO> hall(OrderHallQuery query) {
        LoginUser me = SecurityUtils.currentUser();

        // 双重拦截的第二重：角色注解只能保证"是 COMPANION"，
        // 保证不了"资质已经审过了"。排序键的校验排它后面：
        // 没资质的人连大厅都进不去，先报 2003 比先报参数错更贴近他此刻遇到的问题
        CompanionProfile profile = requireApprovedCompanion(me.userId());
        String sortKey = resolveHallSortKey(query.getSortBy());

        LambdaQueryWrapper<CompanionOrder> wrapper = Wrappers.<CompanionOrder>lambdaQuery()
                .eq(CompanionOrder::getStatus, OrderStatus.PENDING.name())
                // 就诊时间已过的单子不再挂在大厅上：家属没取消，但陪诊员也接不了了
                .gt(CompanionOrder::getVisitTime, LocalDateTime.now())
                // 截断前先按就诊时间排，保证「留下哪几条」是确定的
                // （否则同一批订单每次刷新留下的候选都可能不同）
                .orderByAsc(CompanionOrder::getVisitTime)
                .orderByAsc(CompanionOrder::getId);

        List<Long> rejected = rejectedOrderIds(me.userId());
        if (!rejected.isEmpty()) {
            wrapper.notIn(CompanionOrder::getId, rejected);
        }

        if (StringUtils.hasText(query.getArea())) {
            wrapper.like(CompanionOrder::getAddress, query.getArea().trim());
        }
        if (StringUtils.hasText(query.getHospital())) {
            wrapper.like(CompanionOrder::getHospital, query.getHospital().trim());
        }
        // 大厅按「就诊时间」筛，因为陪诊员是按住哪天有空来接单的
        applyDateRange(wrapper, CompanionOrder::getVisitTime, query.getStartDate(), query.getEndDate());

        // V3.4：候选集 → 五维打分 → 排序 → 内存分页。
        //
        // 为什么不写进 SQL 的 ORDER BY：① 距离要 Haversine，MySQL 无内置函数；
        // ② 服务能力要按「我在这个科室做过多少单」归一化；
        // ③ 「陪诊员无坐标 → 距离记 0 且不推断」需要一条分支 ——
        //    而 SQL 里写 CASE 会让「没数据」与「坐标相同（距离 0）」落进同一个值。
        List<CompanionOrder> candidates = orderMapper.selectList(wrapper);
        int limit = matchingProperties.getHallCandidateLimit();
        if (candidates.size() > limit) {
            log.warn("大厅候选集超过上限，已按就诊时间截断 | companionId={} | 候选={} | 上限={}",
                    me.userId(), candidates.size(), limit);
            candidates = new ArrayList<>(candidates.subList(0, limit));
        }

        sortByMatch(candidates, profile, sortKey);
        return paginate(candidates, query, true);
    }

    /**
     * 解析大厅排序键：请求不传时取配置项，请求与配置走<b>同一份白名单</b>。
     *
     * <p>不在白名单里一律 {@code 400}，不回落推荐序 —— 静默降级会让陪诊员以为
     * 拿到的是「按距离排」的结果，实际是推荐序，拿错误的前提决定接不接单比
     * 直接报错更糟。配置值同样过白名单：「配置写错」和「参数写错」的后果完全一样，
     * 都是拿不到实现的排序键。</p>
     */
    private String resolveHallSortKey(String requestedSort) {
        String raw = StringUtils.hasText(requestedSort)
                ? requestedSort.trim()
                : StringUtils.hasText(fulfillmentProperties.getHallDefaultSort())
                        ? fulfillmentProperties.getHallDefaultSort().trim()
                        : "";
        String sortKey = raw.toUpperCase(Locale.ROOT);
        if (HALL_SORTS.containsKey(sortKey)) {
            return sortKey;
        }
        // 收益优先单独给一句人话：它不是「还没做」，而是「没有可信的金额口径」
        if ("INCOME".equals(sortKey)) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "不支持的排序维度：INCOME；收益优先需要真实结算金额口径，本版未开放，当前仅支持 "
                            + String.join(" / ", HALL_SORTS.keySet()));
        }
        throw new BusinessException(ResultCode.PARAM_ERROR, "不支持的排序维度："
                + (raw.isEmpty() ? "（空值）" : raw) + "；该维度将在后续版本开放，当前仅支持 "
                + String.join(" / ", HALL_SORTS.keySet()));
    }

    /**
     * 按匹配分排序：主键（匹配分降序）→ 兜底键（就诊时间升序 → ID 升序）。
     *
     * <p>ID 那一级不是冗余：同一匹配分、同一就诊时间下若没有它，
     * 同一批数据在两页之间可能重排，分页就不重不漏了。</p>
     */
    private void sortByMatch(List<CompanionOrder> candidates, CompanionProfile profile, String sortKey) {
        if (candidates.size() <= 1) {
            return;
        }
        Map<Long, OrderMatchRank> rankBy = new HashMap<>();
        for (OrderMatchRank rank : matchingService.scoreOrders(profile, candidates)) {
            rankBy.put(rank.orderId(), rank);
        }
        String dimension = HALL_SORTS.getOrDefault(sortKey, SORT_TOTAL);
        Comparator<CompanionOrder> comparator = Comparator
                .comparingDouble((CompanionOrder order) -> -matchScore(rankBy, order, dimension))
                .thenComparing(CompanionOrder::getVisitTime, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(CompanionOrder::getId, Comparator.nullsLast(Comparator.naturalOrder()));
        candidates.sort(comparator);
    }

    /** 取某个订单在指定排序维度上的得分 */
    private static double matchScore(Map<Long, OrderMatchRank> rankBy, CompanionOrder order, String dimension) {
        OrderMatchRank rank = rankBy.get(order.getId());
        if (rank == null) {
            return 0D;
        }
        return SORT_TOTAL.equals(dimension) ? rank.total() : rank.dimension(dimension);
    }

    /**
     * 内存分页（V3.4 大厅）。
     *
     * <p>响应结构与 DB 分页<b>完全一致</b>：{@code total} 仍是「满足大厅筛选条件的
     * 待接单订单数」，{@code pages} 仍是 {@code ceil(total / size)}。
     * 变的只有「哪几条排在前面」。</p>
     */
    private PageResult<OrderVO> paginate(List<CompanionOrder> all, PageQuery<?> query, boolean withAddress) {
        long pageNo = query.normalizedPage();
        long size = query.normalizedSize();
        long total = all.size();
        long pages = (total + size - 1) / size;
        long from = Math.min(Math.max(0L, (pageNo - 1) * size), total);
        long to = Math.min(from + size, total);
        List<CompanionOrder> records = from >= to ? List.of() : all.subList((int) from, (int) to);
        return new PageResult<>(total, pageNo, size, pages, toVoList(records, withAddress));
    }

'''

OLD_TO_VO_PAGE = """    private PageResult<OrderVO> toVoPage(Page<CompanionOrder> page, boolean withAddress) {
        List<CompanionOrder> records = page.getRecords();
        if (records.isEmpty()) {
            return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getPages(), List.of());
        }

        Map<Long, ElderProfile> elders = loadElders(records.stream()"""

NEW_TO_VO_PAGE = """    private PageResult<OrderVO> toVoPage(Page<CompanionOrder> page, boolean withAddress) {
        List<CompanionOrder> records = page.getRecords();
        if (records.isEmpty()) {
            return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getPages(), List.of());
        }
        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getPages(),
                toVoList(records, withAddress));
    }

    /**
     * 实体列表 → VO 列表（批量补老人姓名与陪诊员姓名，避免 N+1）。
     *
     * @param withAddress 是否返回地址（大厅需要，我的订单不需要）
     */
    private List<OrderVO> toVoList(List<CompanionOrder> records, boolean withAddress) {
        if (records.isEmpty()) {
            return List.of();
        }

        Map<Long, ElderProfile> elders = loadElders(records.stream()"""


def main():
    text = TARGET.read_text(encoding="utf-8")

    if "SORT_TOTAL" in text:
        print("已打过补丁，跳过")
        return 0

    start = text.find(OLD_BLOCK_START)
    end = text.find(OLD_BLOCK_END)
    if start < 0 or end < 0:
        print("找不到大厅排序代码块，终止", file=sys.stderr)
        return 1
    text = text[:start] + NEW_BLOCK + text[end:]

    if OLD_TO_VO_PAGE not in text:
        print("找不到 toVoPage，终止", file=sys.stderr)
        return 1
    text = text.replace(OLD_TO_VO_PAGE, NEW_TO_VO_PAGE, 1)

    # toVoList 的收尾：原来的 return 语句要改成返回 list
    old_tail = """        }).toList();

        return new PageResult<>(page.getTotal(), page.getCurrent(), page.getSize(), page.getPages(), list);
    }"""
    new_tail = """        }).toList();

        return list;
    }"""
    if old_tail not in text:
        print("找不到 toVoPage 收尾，终止", file=sys.stderr)
        return 1
    text = text.replace(old_tail, new_tail, 1)

    # import 补齐
    text = text.replace(
        "import org.company.nianglin.config.FulfillmentProperties;",
        "import org.company.nianglin.config.FulfillmentProperties;\nimport org.company.nianglin.config.MatchingProperties;",
        1,
    )
    text = text.replace(
        "import org.company.nianglin.service.support.OrderTransitionService;",
        "import org.company.nianglin.service.MatchingService;\nimport org.company.nianglin.service.support.OrderTransitionService;",
        1,
    )
    text = text.replace(
        "import org.company.nianglin.service.support.OperLogRecorder;",
        "import org.company.nianglin.service.support.OperLogRecorder;",
        1,
    )
    text = text.replace(
        "import org.company.nianglin.util.MaskUtil;",
        "import org.company.nianglin.service.support.matching.MatchingWeights;\nimport org.company.nianglin.service.support.matching.OrderMatchRank;\nimport org.company.nianglin.util.MaskUtil;",
        1,
    )
    text = text.replace(
        "import java.util.ArrayList;",
        "import java.util.ArrayList;\nimport java.util.Comparator;\nimport java.util.HashMap;",
        1,
    )
    text = text.replace(
        "import org.company.nianglin.common.PageResult;",
        "import org.company.nianglin.common.PageQuery;\nimport org.company.nianglin.common.PageResult;",
        1,
    )

    TARGET.write_text(text, encoding="utf-8")
    print("大厅排序已切换到 V3.4 五维加权")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
