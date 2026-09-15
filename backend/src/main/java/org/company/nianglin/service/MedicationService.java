package org.company.nianglin.service;

import org.company.nianglin.common.PageResult;
import org.company.nianglin.dto.MedicationCalendarQuery;
import org.company.nianglin.dto.MedicationPlanCreateDTO;
import org.company.nianglin.dto.MedicationPlanQuery;
import org.company.nianglin.dto.MedicationPlanUpdateDTO;
import org.company.nianglin.dto.MedicationTaskConfirmDTO;
import org.company.nianglin.dto.MedicineQuery;
import org.company.nianglin.vo.MedicationCalendarVO;
import org.company.nianglin.vo.MedicationConfirmVO;
import org.company.nianglin.vo.MedicationPlanVO;
import org.company.nianglin.vo.MedicationTaskVO;
import org.company.nianglin.vo.MedicineVO;

import java.time.LocalDate;
import java.util.List;

/**
 * 用药管理服务。
 *
 * <p>对应 {@code docs/api/05-medication.md} §1 ~ §9。</p>
 *
 * <h3>本模块的边界：记录，不是建议</h3>
 *
 * <p>接口文档开头就把这条写在最前面，因为它最容易在实现期被侵蚀 ——
 * 「顺手」加一个按剂型推断用法的逻辑、「顺手」在药品详情里补一句
 * 常见用量，都会让平台从「记录工具」变成「给出用药意见的主体」。
 * 因此本接口的所有方法都只做四件事：<b>读字典、存计划、算日历、记确认</b>。
 * 任何「由系统推断」的字段都不应该出现在这里，也不应该出现在返回值里。</p>
 *
 * <h3>最后两个方法是给定时任务用的</h3>
 *
 * <p>{@link #generateDailyTasks(LocalDate)} 与 {@link #scanMissedTasks()}
 * <b>没有登录上下文</b>，因此内部不得调用
 * {@code SecurityUtils.currentUser()}，也不做归属校验 ——
 * 它们处理的是全库数据。这一点必须在实现里守住：
 * 一旦有人在这两个方法里加了一句「按当前用户过滤」，
 * 定时任务就只会给恰好为 null 的用户生成任务，而单测若不同时覆盖
 * 「无登录上下文」的场景，这个 bug 会一直静默到线上。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
public interface MedicationService {

    /* ==================== 药品字典 ==================== */

    /** 药品字典分页搜索（通用名 / 商品名模糊 + 剂型筛选） */
    PageResult<MedicineVO> dictPage(MedicineQuery query);

    /** 药品详情；响应必含免责声明 */
    MedicineVO dictDetail(Long medicineId);

    /* ==================== 用药计划 ==================== */

    /** 用药计划列表（按老人档案，须有权访问） */
    PageResult<MedicationPlanVO> planPage(MedicationPlanQuery query);

    /** 新增用药计划，返回计划 ID */
    Long createPlan(MedicationPlanCreateDTO dto);

    /** 修改用药计划（局部更新；只影响未来未生成的任务） */
    void updatePlan(Long planId, MedicationPlanUpdateDTO dto);

    /** 停用用药计划（不物理删除，服药历史保留） */
    void disablePlan(Long planId);

    /* ==================== 服药任务 ==================== */

    /** 服药日历（区间不超过 31 天） */
    MedicationCalendarVO calendar(MedicationCalendarQuery query);

    /** 今日待服任务（老人端大字版首页直接渲染） */
    List<MedicationTaskVO> todayTasks(Long elderId);

    /** 确认服药（允许漏服后补记） */
    MedicationConfirmVO confirm(Long taskId, MedicationTaskConfirmDTO dto);

    /* ==================== 定时任务入口（无登录上下文） ==================== */

    /**
     * 生成指定日期的服药任务（幂等）。
     *
     * <p>唯一索引 {@code uk_plan_date_time (plan_id, plan_time)} 保证重复执行
     * 不会产生重复行，本方法的插入冲突一律静默跳过而不是回滚整批 ——
     * 一次补跑可能涉及成百上千个时间点，因为其中一条已存在就整批失败，
     * 会让「漏服的昨晚补一次」变成永远补不上。</p>
     *
     * @param date 目标日期
     * @return 实际新增的任务条数
     */
    int generateDailyTasks(LocalDate date);

    /**
     * 扫描超时未确认的任务，置为漏服并推送家属站内信。
     *
     * <p>幂等靠 {@code notify_sent} 标记：同一条任务只推一次，
     * 否则每 30 分钟一轮，家属会被同一条漏服消息刷屏。</p>
     *
     * @return 本轮新判定为漏服的任务条数
     */
    int scanMissedTasks();
}
