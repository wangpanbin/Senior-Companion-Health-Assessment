package org.company.nianglin.service.support;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 治理链「处理中超时」判定策略（closure-2026-11 · P1-6）。
 *
 * <p><b>为什么要有这条策略</b>：管理员点了「处理中」之后不点结案，投诉就永远挂在处理中，
 * 用户看到的是「正在处理」—— 一个可以无限期成立的承诺。全仓的定时任务里没有一个
 * 扫投诉，于是这个承诺没有任何人负责。</p>
 *
 * <p><b>只升级可见性，不改状态</b>：超时只是让管理员在列表里看到「这条超时了」，
 * 处置权仍然在人手里。系统不自动驳回、不自动结案 —— 状态变更必须有人负责。</p>
 *
 * <p><b>纯函数</b>：判定只依赖「状态 + 上次推进时间 + 阈值 + 当前时间」，
 * 不碰数据库，因此可以在单测里直接断言天数口径。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
public final class GovernanceTimeoutPolicy {

    /** 「处理中」状态的取值（投诉与产品反馈两链共用同一个字面量） */
    public static final String PROCESSING = "PROCESSING";

    /** 投诉默认超时阈值（天） */
    public static final int DEFAULT_COMPLAINT_TIMEOUT_DAYS = 3;

    /** 产品反馈默认超时阈值（天） */
    public static final int DEFAULT_FEEDBACK_TIMEOUT_DAYS = 7;

    private GovernanceTimeoutPolicy() {
    }

    /**
     * 是否处于「处理中」。
     *
     * <p>只有 {@code PROCESSING} 算：待处理（{@code PENDING}）是刚到管理员手上的活，
     * 不处理是正常的排队；已结案 / 已驳回是终态，没有「超时」可言。</p>
     */
    public static boolean isProcessing(String status) {
        return PROCESSING.equalsIgnoreCase(status);
    }

    /**
     * 超时天数。
     *
     * <p>口径：以「上次推进时间」为起点（从未推进过则用创建时间），满 {@code thresholdDays}
     * 天才算超时；返回已超出的整天数，未超时返回 {@code null}。</p>
     *
     * @param status       当前状态
     * @param lastTouch    上次推进时间（{@code update_time}）；为 {@code null} 时按「未推进过」处理
     * @param createdAt    创建时间（{@code lastTouch} 为 {@code null} 时的回落基准）
     * @param now          当前时间
     * @param thresholdDays 超时阈值（天）
     * @return 超时天数；未超时返回 {@code null}
     */
    public static Integer overdueDays(String status, LocalDateTime lastTouch, LocalDateTime createdAt,
                                       LocalDateTime now, int thresholdDays) {
        if (!isProcessing(status) || thresholdDays <= 0) {
            return null;
        }
        LocalDateTime base = lastTouch != null ? lastTouch : createdAt;
        if (base == null || now == null) {
            return null;
        }
        long days = Duration.between(base, now).toDays();
        if (days < thresholdDays) {
            return null;
        }
        return (int) days;
    }

    /**
     * 是否超时。
     *
     * @see #overdueDays(String, LocalDateTime, LocalDateTime, LocalDateTime, int)
     */
    public static boolean isOverdue(String status, LocalDateTime lastTouch, LocalDateTime createdAt,
                                    LocalDateTime now, int thresholdDays) {
        return overdueDays(status, lastTouch, createdAt, now, thresholdDays) != null;
    }

    /**
     * 提醒档位：每超过一个阈值天数算一档。
     *
     * <p>档位用于「同一档位只提醒一次」：3 天阈值下，3~5 天是第 1 档、6~8 天是第 2 档……
     * 每升一档提醒一次管理员，既不刷屏，也不会让一条投诉彻底没人看见。</p>
     */
    public static int tierOf(int overdueDays, int thresholdDays) {
        if (overdueDays <= 0 || thresholdDays <= 0) {
            return 1;
        }
        return Math.max(1, overdueDays / thresholdDays);
    }
}
