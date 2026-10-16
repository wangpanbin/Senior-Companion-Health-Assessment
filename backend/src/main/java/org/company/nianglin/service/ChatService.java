package org.company.nianglin.service;

import org.company.nianglin.common.PageResult;
import org.company.nianglin.dto.AdminChatOrderQuery;
import org.company.nianglin.dto.ChatConversationQuery;
import org.company.nianglin.dto.ChatReadDTO;
import org.company.nianglin.dto.ChatSendDTO;
import org.company.nianglin.vo.AdminChatOrderVO;
import org.company.nianglin.vo.ChatConversationVO;
import org.company.nianglin.vo.ChatHistoryVO;
import org.company.nianglin.vo.ChatMessageVO;
import org.company.nianglin.vo.ChatReadResultVO;
import org.company.nianglin.vo.ChatUnreadTotalVO;

/**
 * 订单聊天服务（M13，ADR-0011）。
 *
 * <p>对应 {@code docs/api/11-order-chat.md}。会话身份就是陪诊订单（无会话表），
 * 家属与接单陪诊员双向读写，ELDER 只读（通常被 {@code ElderReadOnlyInterceptor}
 * 先行 403），ADMIN 仲裁只读（服务层 {@code 7008} 兜底）。</p>
 *
 * <h3>发送校验链（全部在 {@link #send} 事务内）</h3>
 *
 * <pre>
 * requireInvolved(3001/3004) → requireChatWriter(7008) → requireChatAvailable(7003)
 *   → 内容校验(7004) / 附件归属与类型校验(7005/7006/7007) → 落库 → 同事务后置推送
 * </pre>
 *
 * <p>推送失败只记日志，绝不回滚已落库的消息 —— 对端有 SSE 角标与
 * 重连补洞两条兜底路径。</p>
 *
 * @author 银龄伴诊团队
 * @since M13
 */
public interface ChatService {

    /** 发送消息（仅 FAMILY / COMPANION 本单双方），返回落库后的消息 VO */
    ChatMessageVO send(Long orderId, ChatSendDTO dto);

    /**
     * 游标倒序拉历史：{@code cursor=0} 从最新开始；只读接口，不改已读游标。
     *
     * @param orderId 订单 ID
     * @param cursor  游标（上一页返回的 nextCursor；null/0 表示从最新开始）
     * @param size    每页条数，默认 20，上限 100
     */
    ChatHistoryVO history(Long orderId, Long cursor, Integer size);

    /** 我的会话列表（仅 FAMILY / COMPANION），按最后一条消息 id 倒序，无消息的按订单 id 垫底 */
    PageResult<ChatConversationVO> conversations(ChatConversationQuery query);

    /** 已读上报（幂等 upsert，游标只前进不后退），返回剩余未读数 */
    ChatReadResultVO markRead(Long orderId, ChatReadDTO dto);

    /** 跨所有我会话的未读总数（仅 FAMILY / COMPANION），角标兜底轮询用 */
    ChatUnreadTotalVO unreadTotal();

    /** 管理端：有聊天记录的订单检索（仲裁查看器左栏，只读） */
    PageResult<AdminChatOrderVO> adminChatOrders(AdminChatOrderQuery query);
}
