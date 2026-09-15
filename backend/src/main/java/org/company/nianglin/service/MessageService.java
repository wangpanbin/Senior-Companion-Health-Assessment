package org.company.nianglin.service;

import org.company.nianglin.common.PageResult;
import org.company.nianglin.constant.MessageType;
import org.company.nianglin.dto.MessageQuery;
import org.company.nianglin.vo.MessageReadResultVO;
import org.company.nianglin.vo.MessageVO;
import org.company.nianglin.vo.UnreadCountVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collection;
import java.util.Map;

/**
 * 站内信服务。
 *
 * <p>对应 {@code docs/api/07-message.md} §1 ~ §6。</p>
 *
 * <h3>两类职责，别混在一起看</h3>
 *
 * <ol>
 *   <li><b>面向用户的读写</b>：列表 / 未读数 / 已读 / 删除。这些接口的每一个
 *       都必须在 SQL 与 Service 两层同时限定 {@code receiver_id} ——
 *       少一层就是「能看别人消息」。</li>
 *   <li><b>面向其他模块的发送入口</b>：{@link #send}。M5 打卡、M6 漏服、
 *       M7 评价与投诉、M9 审核与纠纷都调它。
 *       <b>各业务模块禁止自己 INSERT {@code internal_message}</b> ——
 *       那样文案不会走模板、脱敏不会走同一个位置、未读数缓存也不会被更新。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
public interface MessageService {

    /* ==================== 面向用户 ==================== */

    /** 我的消息列表（分页 + 类型 + 已读状态 + 时间区间） */
    PageResult<MessageVO> list(MessageQuery query);

    /** 我的未读数（总数 + 按类型） */
    UnreadCountVO unreadCount();

    /** 标记单条已读；幂等，重复标记不重复扣减 */
    MessageReadResultVO markRead(Long messageId);

    /** 全部 / 某一类标记已读 */
    MessageReadResultVO markAllRead(String type);

    /** 删除消息（只影响自己的视图，不物理删除） */
    void delete(Long messageId);

    /** 建立未读数实时推送通道 */
    SseEmitter subscribe();

    /* ==================== 面向其他模块 ==================== */

    /**
     * 发一条站内信。
     *
     * <p>标题与正文由 {@link org.company.nianglin.util.MessageTemplateUtil} 生成，
     * 超出列宽自动截断。{@code params} 里的姓名类占位符<b>必须由调用方先脱敏</b>：
     * 本方法不知道哪一项是隐私字段，也无从判断该用哪种脱敏规则。</p>
     *
     * @param receiverId 收件人用户 ID，为 {@code null} 时静默跳过（例如订单还没接单，没有陪诊员）
     * @param type       消息类型
     * @param bizId      关联业务 ID，用于点击跳转，可为 {@code null}
     * @param params     模板占位符
     * @return 消息 ID；未发送返回 {@code null}
     */
    Long send(Long receiverId, MessageType type, Long bizId, Map<String, Object> params);

    /**
     * 群发同一条站内信。
     *
     * @param receiverIds 收件人集合，空集合直接返回 0
     * @return 实际发送条数
     */
    int sendBatch(Collection<Long> receiverIds, MessageType type, Long bizId, Map<String, Object> params);
}
