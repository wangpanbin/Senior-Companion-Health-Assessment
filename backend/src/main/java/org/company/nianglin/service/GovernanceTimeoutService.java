package org.company.nianglin.service;

/**
 * 治理链超时扫描（closure-2026-11 · P1-6）。
 *
 * <p>「处理中」是一个可以无限期成立的承诺：管理员点了「处理中」之后不点结案，
 * 用户看到的就是「正在处理」。全仓的定时任务里没有一个扫投诉，于是这个承诺
 * 没有任何人负责。</p>
 *
 * <p>本服务只做一件事：<b>把处理中超时的记录升级出可见性，并提醒管理员</b>。
 * 它不改状态、不改 {@code update_time} —— 状态变更必须有人负责。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
public interface GovernanceTimeoutService {

    /**
     * 扫描「处理中超时」的投诉并提醒管理员。
     *
     * <p><b>幂等</b>：同一档位只提醒一次（Redis {@code SET NX}），
     * 重复扫描不会重复打扰管理员。</p>
     *
     * @return 本轮实际提醒的条数
     */
    int scanOverdueComplaints();
}
