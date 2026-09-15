package org.company.nianglin.service;

import org.company.nianglin.common.PageResult;
import org.company.nianglin.dto.ComplaintCreateDTO;
import org.company.nianglin.dto.ComplaintQuery;
import org.company.nianglin.vo.ComplaintCreateResultVO;
import org.company.nianglin.vo.ComplaintVO;

/**
 * 投诉服务。
 *
 * <p>对应 {@code docs/api/06-review-complaint.md} §5 ~ §7。</p>
 *
 * <h3>投诉双方由订单推导，不由前端传入</h3>
 *
 * <p>文档 §5 实现要点第 1 条：「投诉人 / 被投诉人由订单关系自动推导，
 * <b>不由前端传入</b>（防伪造）」。推导规则只有两条：</p>
 *
 * <pre>
 *   当前用户 == order.familyId      → 被投诉人 = order.companionId（家属投诉陪诊员）
 *   当前用户 == order.companionId   → 被投诉人 = order.familyId   （陪诊员投诉家属）
 *   其余（含 ADMIN）                 → 3004
 * </pre>
 *
 * <p>管理员被刻意排除在这两条之外：管理员确实要处理投诉，但「处理」不等于
 * 「可以以当事人身份发起投诉」。让管理员能提交投诉，等于给了一个不用留痕的
 * 栽赃入口 —— 管理员的动作应该走 M9 的处置流程并写 {@code admin_oper_log}。</p>
 *
 * <h3>同一订单不重复挂未结案投诉</h3>
 *
 * <p>挡在服务层（{@code 409}）而不是靠唯一索引：一个订单可以先后产生多条投诉
 * （这次没处理满意，退号后再投一次是合理诉求），唯一索引会把这种正常场景也堵死。
 * 真正要拦的是「同一条投诉重复提交」，其判据是<b>是否已存在未结案的投诉</b>。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
public interface ComplaintService {

    /**
     * 提交投诉。
     *
     * <p>成功后向全部管理员推 {@code COMPLAINT_SUBMITTED} 站内信，并向被投诉方同步一条。</p>
     *
     * @throws org.company.nianglin.exception.BusinessException
     *         3001 订单不存在 / 3004 非相关方 / 6003 敏感词 / 409 已有未结案投诉
     */
    ComplaintCreateResultVO create(ComplaintCreateDTO dto);

    /** 我的投诉列表（不传 {@code role} 时为「我投诉的 + 投诉我的」并集） */
    PageResult<ComplaintVO> myList(ComplaintQuery query);

    /**
     * 投诉详情。
     *
     * @throws org.company.nianglin.exception.BusinessException 6004 不存在 / 403 非相关方且非管理员
     */
    ComplaintVO detail(Long id);
}
