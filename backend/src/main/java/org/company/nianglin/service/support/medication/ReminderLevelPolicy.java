package org.company.nianglin.service.support.medication;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 漏服提醒的升级时点策略（V5 · 多级升级，docs/api/05-medication.md §2.1）。
 *
 * <h3>为什么基准是 plan_time + 阈值，而不是状态变更时间</h3>
 *
 * <p>任务被判 {@code MISSED} 的时刻在库里<b>没有独立列</b>：{@code update_time}
 * 会被 {@code notify_level} 的每次写入刷新，拿它当基准会让阶梯每派发一级就整体后移一级 ——
 * 永远升不到顶。而「本该被判漏服的时刻」是确定的：{@code plan_time + 阈值}，
 * 与 {@code MedicationServiceImpl#scanMissedTasks} 的判定同源（同一个运营参数），
 * 阈值调大时整条阶梯自动跟着平移。</p>
 *
 * <h3>三档时点（产品定值，非临床标准）</h3>
 *
 * <ul>
 *   <li>L1（家属）：判漏服后 ≥ 30 分钟 —— 参照 Medisafe 的「无动作 30 分钟升级照护者」；</li>
 *   <li>L2（当天陪诊员）：≥ 2 小时；</li>
 *   <li>L3（管理员）：≥ 24 小时 —— 医院人工随访的常见节奏是 24–72 小时起步。</li>
 * </ul>
 *
 * <p>三档是<b>产品自定值</b>（业界无临床指南规定分钟数），如需调整改这里的常量即可；
 * 阈值（判漏服的那一步）走 {@code OperationParam}，运营在线可调。</p>
 *
 * @author 银龄伴诊团队
 * @since V5
 */
public final class ReminderLevelPolicy {

    /** 未到任何升级时点 */
    public static final int LEVEL_NONE = 0;

    /** L1：全部绑定家属（站内信 + 短信） */
    public static final int LEVEL_FAMILY = 1;

    /** L2：当天在途订单的接单陪诊员（站内信 + 短信） */
    public static final int LEVEL_COMPANION = 2;

    /** L3：全部管理员（仅站内信） */
    public static final int LEVEL_ADMIN = 3;

    /** 判漏服后 30 分钟 → L1 */
    public static final Duration LEVEL_1_AFTER = Duration.ofMinutes(30);

    /** 判漏服后 2 小时 → L2 */
    public static final Duration LEVEL_2_AFTER = Duration.ofHours(2);

    /** 判漏服后 24 小时 → L3 */
    public static final Duration LEVEL_3_AFTER = Duration.ofHours(24);

    private ReminderLevelPolicy() {
    }

    /**
     * 计算任务此刻<b>应到达</b>的升级级别（只升不降）。
     *
     * @param planTime        服药计划时间
     * @param thresholdMinutes 漏服判定阈值（运营参数，与扫描判定同源）
     * @param now             当前时刻
     * @return 0–3；{@code planTime} 为 {@code null} 时返回 0（脏数据不升级）
     */
    public static int dueLevel(LocalDateTime planTime, int thresholdMinutes, LocalDateTime now) {
        if (planTime == null || now == null) {
            return LEVEL_NONE;
        }
        LocalDateTime missedAt = planTime.plusMinutes(thresholdMinutes);
        Duration sinceMissed = Duration.between(missedAt, now);
        if (sinceMissed.compareTo(LEVEL_3_AFTER) >= 0) {
            return LEVEL_ADMIN;
        }
        if (sinceMissed.compareTo(LEVEL_2_AFTER) >= 0) {
            return LEVEL_COMPANION;
        }
        if (sinceMissed.compareTo(LEVEL_1_AFTER) >= 0) {
            return LEVEL_FAMILY;
        }
        return LEVEL_NONE;
    }
}
