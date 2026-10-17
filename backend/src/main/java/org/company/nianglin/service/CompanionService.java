package org.company.nianglin.service;

import org.company.nianglin.dto.CompanionApplyDTO;
import org.company.nianglin.dto.CompanionWorkStatusUpdateDTO;
import org.company.nianglin.vo.CompanionApplicationVO;
import org.company.nianglin.vo.CompanionApplyResultVO;
import org.company.nianglin.vo.CompanionProfileVO;

/**
 * 陪诊员资质服务。
 *
 * <p>对应 {@code docs/api/02-elder-family.md} §3 ~ §5。</p>
 *
 * <p><b>本模块只负责「提交」与「查询」</b>。「审核通过 / 驳回」属于管理后台，
 * 按 {@code docs/api/02-elder-family.md} 的迭代标注排在 W13（M9），
 * 与用户封禁、订单纠纷一起做 —— 因为审核动作要写 {@code admin_oper_log}、
 * 要发站内信通知申请人，这些配套设施都在 M9。</p>
 *
 * <p>提交申请<b>不会</b>改变用户角色。角色升级只发生在管理员审核通过那一刻，
 * 这样「提交了申请就以为自己能接单」的误解在数据层面不成立。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
public interface CompanionService {

    /** 提交资质申请，返回申请结果；已有待审核申请或已通过审核时拒绝 */
    CompanionApplyResultVO apply(CompanionApplyDTO dto);

    /** 查询自己最近一次资质申请状态；从未申请过返回 {@code null} */
    CompanionApplicationVO myApplication();

    /** 陪诊员公开资料（不返回身份证、电话、证件、驳回原因） */
    CompanionProfileVO publicProfile(Long companionUserId);

    /**
     * 陪诊员切换自己的接单状态（V3.1 · T0）。
     *
     * <p><b>不变量：只改档案上的接单状态，不触碰任何在手订单。</b>
     * 已 {@code ACCEPTED} / {@code IN_SERVICE} 的订单继续按订单状态机推进 ——
     * 「自主控制接单」不能被实现成「自主放弃在手服务」。</p>
     *
     * <p>判定顺序：先看资质（未通过返回 {@code 2003}），再看状态值是否合法。
     * 资质已通过但状态写错时，优先报「状态值非法」而不是「资质未通过」，
     * 因为前者才是用户此刻真正要改的东西。</p>
     *
     * @throws org.company.nianglin.exception.BusinessException 2003 资质未通过 /
     *                                                         2007 无陪诊员档案 /
     *                                                         400 状态字面量非法
     */
    CompanionProfileVO updateWorkStatus(CompanionWorkStatusUpdateDTO dto);
}
