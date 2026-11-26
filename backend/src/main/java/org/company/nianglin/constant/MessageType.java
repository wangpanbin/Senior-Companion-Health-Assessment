package org.company.nianglin.constant;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

/**
 * 站内信消息类型（{@code internal_message.type}）。
 *
 * <p>对应 {@code docs/api/07-message.md} §一「消息类型」表。
 * 一期没有 IM（计划书的范围控制），站内信只做<b>「系统 → 用户」单向通知</b>，
 * 因此这里列出的每一个类型都只有一个「谁触发、发给谁」的固定方向。</p>
 *
 * <p>{@link #getBizType()} 决定点击消息后跳到哪个模块；
 * 标题与正文模板在 {@code util/MessageTemplateUtil} 里，<b>不放在本枚举</b> ——
 * 文案会随产品迭代改，而类型枚举一旦改动就要同步改数据库里的历史值，
 * 两者放在一起会让人不敢动文案。</p>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
@Getter
public enum MessageType {

    ORDER_CREATED("新订单", MessageBizType.ORDER),
    ORDER_ACCEPTED("订单已接单", MessageBizType.ORDER),
    ORDER_PROGRESS("陪诊进度", MessageBizType.ORDER),
    ORDER_COMPLETED("服务已完成", MessageBizType.ORDER),
    ORDER_CANCELLED("订单已取消", MessageBizType.ORDER),
    AUDIT_RESULT("资质审核结果", MessageBizType.AUDIT),
    MEDICATION_REMIND("用药提醒", MessageBizType.MEDICATION),
    /**
     * 漏服提醒（V4 · E1a）：发给该老人<b>当天</b> ACCEPTED / IN_SERVICE 订单的接单陪诊员。
     *
     * <p>与 {@link #MEDICATION_REMIND}（发家属）分开成独立类型：两侧的跳转不同
     * （家属跳用药页、陪诊员跳订单执行页），且接收人互不重叠，
     * 挤在一个类型里就得靠占位符猜身份。文案只含药名与时间，
     * 禁含剂量（发送前过 {@code ComplianceCheckUtil} 词表）。</p>
     */
    MEDICATION_MISSED("漏服提醒", MessageBizType.MEDICATION),
    /**
     * 漏服三级升级的 L3（V5）：发给<b>全部管理员</b>，{@code bizId=elderId}，
     * 仅站内信不入短信队列。
     *
     * <p>与 {@link #MEDICATION_MISSED} 分开成独立类型：接收人完全不同
     * （管理员不是当事人），文案要陈述的是「24 小时无人确认」这个升级事实，
     * 挤在一个类型里会让管理员的消息筛选失效。文案只含药名与时间，
     * 禁含剂量（发送前过 {@code ComplianceCheckUtil} 词表）。</p>
     */
    MEDICATION_ESCALATED("漏服升级提醒", MessageBizType.MEDICATION),
    COMPLAINT_SUBMITTED("新投诉待处理", MessageBizType.COMPLAINT),
    COMPLAINT_HANDLED("投诉处理结果", MessageBizType.COMPLAINT),
    /**
     * 陪诊员回复了评价（E4）。
     *
     * <p>接收人是评价人（家属），{@code bizId=orderId}；文案携带订单号与
     * 回复前 30 字摘要，避免站内信成为绕过脱敏的正文泄露出口。</p>
     */
    REVIEW_REPLIED("评价被回复", MessageBizType.ORDER),
    /**
     * 陪诊员对评价发起申诉，通知被申诉家属 + 全部管理员（E4）。
     *
     * <p>{@code bizId=complaintId}；跳转投诉详情由 {@code linkUrl} 给出。</p>
     */
    REVIEW_APPEAL_SUBMITTED("收到评价申诉", MessageBizType.COMPLAINT),
    /**
     * 管理员裁定评价无效（E4）。
     *
     * <p>接收人是评价家属 + 陪诊员，{@code bizId=orderId}；文案携带订单号与
     * 裁定理由前 30 字摘要。</p>
     */
    REVIEW_INVALIDATED("评价被裁定无效", MessageBizType.ORDER),
    /**
     * 订单等待较久，风险升为「等待时间较长」（V3.1）。
     *
     * <p>接收人是<b>下单家属</b>，{@code bizId=orderId}。文案只陈述事实
     * （「还在等、系统在扩大匹配范围」），不含任何医疗判断。</p>
     */
    FULFILLMENT_WARNING("订单匹配时间较长", MessageBizType.ORDER),
    /**
     * 订单履约风险较高（V3.1）。
     *
     * <p>接收人是下单家属。此时家属已可催单，文案不主动教用户去催 ——
     * 按钮本身就在订单详情里，站内信只负责告知状态变化。</p>
     */
    FULFILLMENT_URGENT("订单需要尽快安排陪诊员", MessageBizType.ORDER),
    /**
     * 家属催单（V3.1）。
     *
     * <p>接收人是<b>全部资质已通过且处于可接单状态的陪诊员</b>，
     * {@code bizId=orderId}。不是管理员 —— 管理员的入口是履约风险中心，
     * 不是站内信；把两者混在一起会让管理员的消息列表变成噪音源。</p>
     */
    ORDER_URGED("有订单在等待陪诊员", MessageBizType.ORDER),
    /**
     * 订单改派陪诊员（V3.1 · T7）。
     *
     * <p>接收人是下单家属 + 目标陪诊员 + 原陪诊员（若有），
     * {@code bizId=orderId}。三类接收方用同一模板，靠占位符区分措辞。</p>
     */
    ORDER_REASSIGNED("订单已改派陪诊员", MessageBizType.ORDER),
    /**
     * 就诊前一天的检查准备提醒（V5 · B）。
     *
     * <p>接收人是<b>下单家属 + 就诊老人本人</b>，{@code bizId=orderId}。
     * 消息只陈述「明天有就诊安排 + 医院 + 科室」这个事实；准备要求本身
     * 去查静态字典（{@code GET /api/exam-prep}）或助手 FAQ，不由本消息生成
     * （红线-1）。发送前过 {@code ComplianceCheckUtil} 词表。</p>
     */
    ORDER_EXAM_PREP("检查准备提醒", MessageBizType.ORDER),
    /**
     * 健康证即将到期或已过期（V5 · C1）。
     *
     * <p>接收人是<b>本人 + 全部管理员</b>，{@code bizId=companionProfileId}；
     * 两侧用同一模板靠 {@code audience} 占位符分流措辞（与 {@link #ORDER_REASSIGNED} 同法）。
     * 文案只陈述到期事实与后果（无法接单），不含任何医疗判断。</p>
     */
    HEALTH_CERT_EXPIRING("健康证到期提醒", MessageBizType.AUDIT),
    /**
     * 产品反馈处理结果（V6）。
     *
     * <p>接收人是<b>反馈提交人</b>（家属 / 陪诊员），{@code bizId=feedbackId}，
     * 点击跳客户端反馈页 {@code /feedback}。<b>只在终态（已采纳 / 未采纳）发送</b> ——
     * 中间态也发的话，用户会收到一条「已处理完成」，而实际处理还在进行。</p>
     *
     * <p>正文里的处理说明摘要由调用方预先截断到 100 字以内（与 {@link #REVIEW_REPLIED}
     * 的 {@code replyDigest} 同款约定）；发送前已由 {@code FeedbackServiceImpl}
     * 过敏感词 + 医疗红线两套词表（平台产出口径）。</p>
     */
    FEEDBACK_HANDLED("反馈处理结果", MessageBizType.FEEDBACK),
    SYSTEM_NOTICE("系统公告", MessageBizType.SYSTEM),
    ;

    /** 中文类型名 */
    private final String label;

    /** 关联业务域 */
    private final String bizType;

    MessageType(String label, String bizType) {
        this.label = label;
        this.bizType = bizType;
    }

    /** 按枚举名反查（忽略大小写），非法值返回 {@code null} */
    public static MessageType of(String name) {
        if (name == null) {
            return null;
        }
        for (MessageType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }

    /**
     * 解析逗号分隔的多值类型参数。
     *
     * <p>非法取值直接抛错而不是忽略：前端传 {@code ORDER_ACCPETED}（拼错）却拿到全部消息，
     * 会以为筛选生效了，然后拿这份数据做出错误判断。与 M4 的状态参数处理保持一致。</p>
     *
     * @param raw 原始参数，可为空
     * @return 解析出的类型列表；入参为空返回空列表
     * @throws org.company.nianglin.exception.BusinessException 取值非法时
     */
    public static List<MessageType> parseList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .map(value -> {
                    MessageType type = of(value);
                    if (type == null) {
                        throw new org.company.nianglin.exception.BusinessException(
                                org.company.nianglin.common.ResultCode.PARAM_ERROR,
                                "消息类型取值不合法：" + value);
                    }
                    return type;
                })
                .toList();
    }

    /** 中文展示名；非法值原样返回，避免展示层因为一条脏数据 500 */
    public static String labelOf(String name) {
        MessageType type = of(name);
        return type == null ? name : type.getLabel();
    }
}
