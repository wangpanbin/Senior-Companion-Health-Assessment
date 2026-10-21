package org.company.nianglin.service;

import org.company.nianglin.dto.AssistantChatDTO;
import org.company.nianglin.vo.AssistantChatVO;

/**
 * 家属智能助手（V3.7 · M14）。
 *
 * <h3>本服务是「门面」，不是「大脑」</h3>
 *
 * <p>三件事按顺序做（ADR-0020）：</p>
 * <ol>
 *   <li><b>限流</b> —— 每次问答都要调一次外部模型，成本是真的；</li>
 *   <li><b>上下文装配</b> —— 把订单上下文、候选陪诊员、老人列表查好，交给 Python；</li>
 *   <li><b>兜底</b> —— Python 不可用时，本地读同一份 FAQ 回答，<b>不让助手整体挂掉</b>。</li>
 * </ol>
 *
 * <p>「推荐陪诊员」的排序<b>不在这里算</b>，也<b>不在 Python 里算</b> ——
 * 它永远是 V3.4 规则引擎的活（{@code OrderService#recommendCompanions}）。
 * 本门面只是把那份结果原样转交，让 Python 讲成人话。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.7
 */
public interface AssistantService {

    /**
     * 家属问答。
     *
     * @param dto 问答入参（不含 userId —— 身份取自令牌）
     * @return 问答结果
     * @throws org.company.nianglin.exception.BusinessException
     *         10001 提问过于频繁 / 10002 助手服务不可用（且兜底也失败） /
     *         10003 问题内容含违规表述 / 3004 订单非本人 / 2006 无权操作该老人
     */
    AssistantChatVO chat(AssistantChatDTO dto);
}
