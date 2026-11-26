package org.company.nianglin.service;

import org.company.nianglin.common.PageResult;
import org.company.nianglin.dto.AdminFeedbackQuery;
import org.company.nianglin.dto.FeedbackCreateDTO;
import org.company.nianglin.dto.FeedbackHandleDTO;
import org.company.nianglin.dto.FeedbackQuery;
import org.company.nianglin.vo.FeedbackCreateResultVO;
import org.company.nianglin.vo.FeedbackHandleResultVO;
import org.company.nianglin.vo.FeedbackVO;

/**
 * 产品反馈服务（V6）。
 *
 * <p>对应 {@code docs/api/15-product-feedback.md}。与 {@link ComplaintService} 的关系：
 * <b>并存而非替代</b> —— 投诉管「某笔订单上 A 与 B 的纠纷」，本服务管「用户对产品本身的意见」。
 * 两者在数据、状态机、可见范围、审计口径上都不共享一行代码。</p>
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
public interface FeedbackService {

    /**
     * 提交反馈（客户端）。
     *
     * <p>提交人 / 角色取自登录态；正文只过敏感词（用户提「用药页能不能提醒别吃错」
     * 是正当建议，套医疗红线词表会误杀）。</p>
     */
    FeedbackCreateResultVO create(FeedbackCreateDTO dto);

    /** 我的反馈列表（分页，可见范围恒为本人提交的） */
    PageResult<FeedbackVO> myList(FeedbackQuery query);

    /**
     * 反馈详情。
     *
     * <p>仅提交人本人与 ADMIN 可见；其余（含「记录不存在」）一律
     * {@code 12001} —— 刻意不区分，避免按 ID 枚举他人反馈是否存在。</p>
     */
    FeedbackVO detail(Long id);

    /** 管理端反馈列表（全部，不收敛；待处理优先） */
    PageResult<FeedbackVO> adminList(AdminFeedbackQuery query);

    /**
     * 处理反馈（管理端）。
     *
     * <p>状态只可正向流转；处理说明每次必填、且过敏感词 + 医疗红线两套词表
     * （它会经站内信原样回给提交人，属平台产出口径）；终态才下发与发信。</p>
     */
    FeedbackHandleResultVO handle(Long id, FeedbackHandleDTO dto);
}
