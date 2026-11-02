package org.company.nianglin.service.support.medication;

import org.company.nianglin.entity.MedicationTask;

/**
 * 漏服提醒触达策略（V4 · E1a）。
 *
 * <p>为什么抽接口而不是在 {@code MedicationServiceImpl} 里继续堆私有方法：
 * V4 计划（{@code docs/plan/iteration-plan-v4.md} 任务 4.3 / 5.2）明确提醒渠道会演进 ——
 * P0 阶段只有站内信，P1 阶段（任务 5.2）要加短信 / 语音外呼。
 * 抽策略接口让「加渠道」变成「加一个实现类」，
 * 而不是把 {@code scanMissedTasks} 改成一个认识所有渠道的巨无霸。</p>
 *
 * <p><b>边界</b>：本接口只管「提醒怎么发出去」，不管「任务该不该判漏服」
 * （那是 {@code MedicationService#scanMissedTasks} 的事），也不维护
 * {@code notify_sent} 标记（留在调用方，与任务状态写在一起）。
 * 这不是全局通知框架，只服务于漏服提醒这一个场景。</p>
 *
 * @author 银龄伴诊团队
 * @since V4
 */
public interface ReminderDispatcher {

    /**
     * 漏服提醒触达：先发给该老人全部绑定家属；若该老人<b>当天</b>有
     * {@code ACCEPTED} / {@code IN_SERVICE} 陪诊订单，同步发给该单接单陪诊员。
     *
     * <p>实现方负责单个渠道的失败隔离 —— 任一渠道发送失败只记日志，
     * 不抛出、不影响其他渠道，更不回滚漏服判定。</p>
     *
     * @param task 刚被判为 MISSED 的服药任务（快照字段已齐：药名 / 计划时间 / 老人 ID）
     */
    void dispatchMissedReminder(MedicationTask task);
}
