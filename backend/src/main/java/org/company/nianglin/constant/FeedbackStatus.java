package org.company.nianglin.constant;

import lombok.Getter;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 产品反馈处理状态机（{@code product_feedback.status}）。
 *
 * <p><b>只可正向流转，不允许回到 PENDING。</b></p>
 *
 * <pre>
 *   PENDING(待处理) ──► PROCESSING(处理中) ──► RESOLVED(已采纳)
 *        │                     │
 *        └─────────────────────┴──► REJECTED(未采纳)
 * </pre>
 *
 * <h3>为什么照抄 {@link ComplaintStatus} 而不简化成两态</h3>
 *
 * <p>两态（待处理 / 已处理）看着更省事，但它丢掉了「谁在什么时候接手了这条建议」
 * 这个最低限度的可追溯信息。反馈最怕的不是被驳回，而是<b>提了没人理</b> ——
 * 中间那一格 PROCESSING 就是「有人看了」的凭据，而对应用户端也是一句
 * 说得出口的解释。</p>
 *
 * <h3>为什么不许 PENDING 直接跳终态</h3>
 *
 * <p>与投诉同理：结案 / 驳回等价于「我判定过了」，需要理由（{@code handleResult}）
 * 也需要留痕。多一次「接手」动作，换来的是每份结论背后都有一个明确的操作时刻。</p>
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
@Getter
public enum FeedbackStatus {

    PENDING("待处理", 1),
    PROCESSING("处理中", 2),
    RESOLVED("已采纳", 3),
    REJECTED("未采纳", 4),
    ;

    private final String label;

    /** 排序权重，用于列表优先展示待办 */
    private final int sort;

    FeedbackStatus(String label, int sort) {
        this.label = label;
        this.sort = sort;
    }

    private static final Map<FeedbackStatus, Set<FeedbackStatus>> TRANSITIONS = Map.of(
            PENDING, EnumSet.of(PROCESSING),
            PROCESSING, EnumSet.of(RESOLVED, REJECTED),
            RESOLVED, EnumSet.noneOf(FeedbackStatus.class),
            REJECTED, EnumSet.noneOf(FeedbackStatus.class)
    );

    /** 是否已到终态（终态才把处理说明下发给提交人、才发站内信） */
    public boolean isTerminal() {
        return this == RESOLVED || this == REJECTED;
    }

    /** 是否允许从当前状态流转到目标状态 */
    public boolean canTransitTo(FeedbackStatus target) {
        return target != null && TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /** 按名称反查（忽略大小写，同时兼容传中文 label），非法值返回 {@code null} */
    public static FeedbackStatus of(String name) {
        if (name == null) {
            return null;
        }
        for (FeedbackStatus s : values()) {
            if (s.name().equalsIgnoreCase(name) || s.label.equals(name)) {
                return s;
            }
        }
        return null;
    }

    /** 中文展示名；非法值原样返回 */
    public static String labelOf(String name) {
        FeedbackStatus s = of(name);
        return s == null ? name : s.getLabel();
    }
}
