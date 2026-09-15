package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 老人行动能力（{@code elder_profile.mobility_level}）。
 *
 * <p>建档时采集，用途是<b>给陪诊员做匹配</b>：需要搀扶或轮椅的老人，
 * 陪诊员得知道要准备什么，也影响同一时段能接几单。</p>
 *
 * <p>⚠️ 这是<b>照护需求标签，不是医学评估</b>。枚举值刻意只用「自理 / 需搀扶 / 轮椅」这类
 * 生活化描述，不出现任何分级、评分或诊断性词汇（合规红线第 1 条）。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Getter
public enum MobilityLevel {

    SELF("可自理"),
    ASSIST("需搀扶"),
    WHEELCHAIR("需轮椅"),
    ;

    private final String label;

    MobilityLevel(String label) {
        this.label = label;
    }

    public static MobilityLevel of(String name) {
        if (name == null) {
            return null;
        }
        for (MobilityLevel l : values()) {
            if (l.name().equalsIgnoreCase(name)) {
                return l;
            }
        }
        return null;
    }

    public static String labelOf(String name) {
        MobilityLevel l = of(name);
        return l == null ? name : l.getLabel();
    }
}
