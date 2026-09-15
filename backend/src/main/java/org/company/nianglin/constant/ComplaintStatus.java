package org.company.nianglin.constant;

import lombok.Getter;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 投诉处理状态机（{@code complaint.status}）。
 *
 * <p><b>只可正向流转，不允许回到 PENDING。</b></p>
 *
 * <pre>
 *   PENDING(待处理) ──► PROCESSING(处理中) ──► RESOLVED(已结案)
 *        │                     │
 *        └─────────────────────┴──► REJECTED(已驳回)
 * </pre>
 *
 * <h3>为什么驳回也必须从「处理中」走</h3>
 *
 * <p>允许 {@code PENDING → REJECTED} 会让管理员在<b>还没看过证据</b>时一键关掉投诉，
 * 而「驳回」这个动作在业务上等价于「我判定投诉不成立」——
 * 它需要理由（{@code handleResult}），也需要留痕。多一次「接单」动作，
 * 换来的是每条驳回记录背后都有一个明确的管理员操作时刻。</p>
 *
 * <p>与 M4 的订单状态机同一套路数：终态不可回退，非法流转一律拒绝。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@Getter
public enum ComplaintStatus {

    PENDING("待处理", 1),
    PROCESSING("处理中", 2),
    RESOLVED("已结案", 3),
    REJECTED("已驳回", 4),
    ;

    private final String label;

    /** 排序权重，用于列表优先展示待办 */
    private final int sort;

    ComplaintStatus(String label, int sort) {
        this.label = label;
        this.sort = sort;
    }

    private static final Map<ComplaintStatus, Set<ComplaintStatus>> TRANSITIONS = Map.of(
            PENDING, EnumSet.of(PROCESSING),
            PROCESSING, EnumSet.of(RESOLVED, REJECTED),
            RESOLVED, EnumSet.noneOf(ComplaintStatus.class),
            REJECTED, EnumSet.noneOf(ComplaintStatus.class)
    );

    /** 是否已到终态 */
    public boolean isTerminal() {
        return this == RESOLVED || this == REJECTED;
    }

    /** 是否允许从当前状态流转到目标状态 */
    public boolean canTransitTo(ComplaintStatus target) {
        return target != null && TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /** 「未处理」 = 还没到终态，用于「同一订单不能重复提交投诉」的判断 */
    public boolean isOpen() {
        return !isTerminal();
    }

    /** 按名称反查（忽略大小写），非法值返回 {@code null} */
    public static ComplaintStatus of(String name) {
        if (name == null) {
            return null;
        }
        for (ComplaintStatus s : values()) {
            if (s.name().equalsIgnoreCase(name) || s.label.equals(name)) {
                return s;
            }
        }
        return null;
    }

    /** 中文展示名；非法值原样返回 */
    public static String labelOf(String name) {
        ComplaintStatus s = of(name);
        return s == null ? name : s.getLabel();
    }
}
