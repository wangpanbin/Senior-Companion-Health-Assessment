package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 服药任务状态（{@code medication_task.status}）。
 *
 * <p>三个状态之间的迁移只有两条路：</p>
 *
 * <pre>
 *   PENDING ──确认服药──► TAKEN
 *      │
 *      └──超时未确认（定时任务）──► MISSED ──补记──► TAKEN（同时 was_missed = 1）
 * </pre>
 *
 * <h3>「补记」为什么保留 was_missed</h3>
 *
 * <p>把 {@code MISSED} 改回 {@code TAKEN} 会让「本次到底漏没漏」这个事实消失，
 * 而漏服率正是本模块唯一的量化指标（答辩要展示「从 X% 降到 Y%」）。
 * 所以最终状态与「是否曾经漏过」分开存：
 * {@code status} 表示现在看到的结果，{@code was_missed} 表示统计口径。
 * 统计漏服率时用 {@code was_missed}，不看 {@code status}。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Getter
public enum MedicationTaskStatus {

    PENDING("待服"),
    TAKEN("已服"),
    MISSED("漏服"),
    ;

    /** 中文展示名 */
    private final String label;

    MedicationTaskStatus(String label) {
        this.label = label;
    }

    /** 按名称反查（忽略大小写），非法值返回 {@code null} */
    public static MedicationTaskStatus of(String name) {
        if (name == null) {
            return null;
        }
        for (MedicationTaskStatus s : values()) {
            if (s.name().equalsIgnoreCase(name) || s.label.equals(name)) {
                return s;
            }
        }
        return null;
    }

    /** 中文展示名；非法值原样返回，避免展示层因为一条脏数据 500 */
    public static String labelOf(String name) {
        MedicationTaskStatus s = of(name);
        return s == null ? name : s.getLabel();
    }

    /** 是否还需要被定时任务关注（终态不再扫描） */
    public boolean isSettled() {
        return this != PENDING;
    }
}
