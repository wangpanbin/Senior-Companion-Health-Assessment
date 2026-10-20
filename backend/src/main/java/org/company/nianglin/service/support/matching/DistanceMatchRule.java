package org.company.nianglin.service.support.matching;

import org.company.nianglin.util.GeoUtil;

/**
 * 距离匹配规则（V3.4 · FR-Rule）。
 *
 * <h3>口径</h3>
 *
 * <pre>
 * 距离 ≤ 满量程   → 1 - 距离 / 满量程
 * 距离 &gt; 满量程  → 0
 * </pre>
 *
 * <h3>「无坐标 → 记 0」是本规则最重要的一条</h3>
 *
 * <p>陪诊员档案的坐标是 V3.4 才加的列，老数据全是 {@code NULL}。
 * 如果把 {@code NULL} 当成 {@code (0,0)} 去算距离，会得到一个
 * 「离几内亚湾 2000 公里」的荒谬结果，然后在排序里把真正合适的人挤下去。</p>
 *
 * <p>所以：**没有坐标就记 0 并在说明里写明「未填写坐标，距离维度记 0」**。
 * 0 在这里表示「这一维没法算」，不是「距离很远」——
 * 这两件事由 {@link DimensionResult#note()} 分开表达。</p>
 *
 * <p>距离一律复用 {@link GeoUtil#distanceMeters}（已有 Haversine 实现）——
 * 两份 Haversine 迟早会漂。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
public class DistanceMatchRule implements MatchingRule {

    /** 距离归一化的满量程（米） */
    private final int maxMeters;

    public DistanceMatchRule(int maxMeters) {
        this.maxMeters = maxMeters;
    }

    @Override
    public String dimension() {
        return MatchingWeights.DIMENSION_DISTANCE;
    }

    @Override
    public String displayName() {
        return "距离";
    }

    @Override
    public DimensionResult evaluate(MatchingCandidate candidate, MatchTarget target) {
        if (!candidate.hasCoordinates()) {
            return DimensionResult.degraded(0.0D, "未填写坐标", "未填写坐标，距离维度记 0");
        }
        if (!target.hasCoordinates()) {
            return DimensionResult.degraded(0.0D, "订单无坐标", "订单无坐标，距离维度记 0");
        }

        int meters = GeoUtil.distanceMeters(candidate.longitude(), candidate.latitude(),
                target.longitude(), target.latitude());
        if (meters < 0) {
            return DimensionResult.degraded(0.0D, "坐标无法计算距离", "坐标非法，距离维度记 0");
        }
        if (meters >= maxMeters) {
            return DimensionResult.of(0.0D, "超出服务半径");
        }
        double score = 1.0D - ((double) meters / maxMeters);
        return DimensionResult.of(score, "距离约 " + (meters / 1000) + " 公里");
    }
}
