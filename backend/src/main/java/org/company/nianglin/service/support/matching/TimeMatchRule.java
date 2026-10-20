package org.company.nianglin.service.support.matching;

import java.time.LocalTime;

/**
 * 时间匹配规则（V3.4 · FR-Rule）。
 *
 * <h3>口径</h3>
 *
 * <pre>
 * 就诊时间落在某个可服务时段内   → 1.0
 * 同一天但不在任何时段内         → 0.5
 * 其它（不同天 / 无时段）        → 0
 * </pre>
 *
 * <p>「同一天但时段不对」给 0.5 而不是 0：那天他确实上班，只是这个钟点不行 ——
 * 与「那天他根本不上班」不是一回事。</p>
 *
 * <h3>周内第几天</h3>
 *
 * <p>用 {@code DayOfWeek#getValue()}（1=周一 … 7=周日），与
 * {@code companion_available_slot.weekday} 的定义一致，也与 ISO 周一致。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
public class TimeMatchRule implements MatchingRule {

    @Override
    public String dimension() {
        return MatchingWeights.DIMENSION_TIME;
    }

    @Override
    public String displayName() {
        return "时间";
    }

    @Override
    public DimensionResult evaluate(MatchingCandidate candidate, MatchTarget target) {
        if (target.visitTime() == null) {
            return DimensionResult.degraded(0.0D, "订单无就诊时间", "订单无就诊时间，时间维度记 0");
        }
        if (candidate.slots() == null || candidate.slots().isEmpty()) {
            return DimensionResult.degraded(0.0D, "未登记可服务时段",
                    "未登记可服务时段，时间维度记 0");
        }

        int weekday = target.visitTime().getDayOfWeek().getValue();
        LocalTime time = target.visitTime().toLocalTime();
        boolean sameDay = false;

        for (MatchingCandidate.AvailableSlot slot : candidate.slots()) {
            if (slot.weekday() != weekday) {
                continue;
            }
            sameDay = true;
            // 结束时刻小于起始时刻 = 跨零点的时段（如 22:00–06:00）
            boolean inside = slot.start().isBefore(slot.end())
                    ? (!time.isBefore(slot.start()) && time.isBefore(slot.end()))
                    : (!time.isBefore(slot.start()) || time.isBefore(slot.end()));
            if (inside) {
                return DimensionResult.of(1.0D, "就诊时间在可服务时段内");
            }
        }

        if (sameDay) {
            return DimensionResult.of(0.5D, "当天可服务，但就诊时刻不在时段内");
        }
        return DimensionResult.of(0.0D, "就诊当天不在可服务日");
    }
}
