package org.company.nianglin.util;

import org.company.nianglin.constant.MessageBizType;
import org.company.nianglin.constant.MessageType;

import java.util.Map;

/**
 * 站内信文案模板（后端唯一真源）。
 *
 * <p>{@code docs/api/07-message.md} §三 明确要求：「统一在后端维护，<b>禁止前端拼文案</b>」。
 * 理由不只是省事 —— 文案里要嵌业务字段（订单号、老人姓名、药名），
 * 如果由前端拼，那么<b>脱敏就必须在前端做</b>，
 * 而脱敏规则一旦两边各写一份，就一定会有一份先过期，泄露的往往是更旧的那一份。</p>
 *
 * <h3>长度必须截断</h3>
 *
 * <p>数据库是 {@code title VARCHAR(50)} / {@code content VARCHAR(500)}。
 * 模板里的占位符（尤其是 {@code remark}、{@code handleResult} 这类用户输入）
 * 长度不受本类控制，拼接后超长会让 {@code INSERT} 直接抛
 * 「Data too long for column」，而消息发送通常发生在<b>业务事务内部</b>：
 * 一条超长文案会把「完成服务」这种主流程一起回滚掉。
 * 所以出口统一截断，宁可消息尾巴少几个字，也不能让主业务失败。</p>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
public final class MessageTemplateUtil {

    /** 与 {@code internal_message.title} 的列宽一致 */
    public static final int MAX_TITLE_LENGTH = 50;

    /** 与 {@code internal_message.content} 的列宽一致 */
    public static final int MAX_CONTENT_LENGTH = 500;

    /** 取值缺失时的占位串 */
    private static final String UNKNOWN = "—";

    private MessageTemplateUtil() {
    }

    /** 标题模板：固定短语，不带占位符 */
    public static String title(MessageType type) {
        return switch (type) {
            case ORDER_CREATED -> "新陪诊订单";
            case ORDER_ACCEPTED -> "订单已接单";
            case ORDER_PROGRESS -> "陪诊进度更新";
            case ORDER_COMPLETED -> "服务已完成";
            case ORDER_CANCELLED -> "订单已取消";
            case AUDIT_RESULT -> "资质审核结果";
            case MEDICATION_REMIND -> "用药提醒";
            case COMPLAINT_SUBMITTED -> "新投诉待处理";
            case COMPLAINT_HANDLED -> "投诉处理结果";
            case SYSTEM_NOTICE -> "系统公告";
        };
    }

    /**
     * 正文模板。占位符由调用方通过 {@code params} 填充。
     *
     * <p>缺失的占位符渲染成「—」而不是 {@code null}：一条写着
     * 「陪诊员 null 已接下订单 NL2026…」的通知，用户只会当成系统坏了。</p>
     */
    public static String content(MessageType type, Map<String, ?> params) {
        Map<String, ?> p = params == null ? Map.of() : params;
        return switch (type) {
            case ORDER_CREATED -> "有一笔新订单 %s（%s %s），请及时接单。".formatted(
                    value(p, "orderNo"), value(p, "visitTime"), value(p, "hospital"));
            case ORDER_ACCEPTED -> "陪诊员 %s 已接下订单 %s。".formatted(
                    value(p, "companionName"), value(p, "orderNo"));
            case ORDER_PROGRESS -> "%s：%s（订单 %s）".formatted(
                    value(p, "nodeLabel"), value(p, "remark"), value(p, "orderNo"));
            case ORDER_COMPLETED -> "订单 %s 已完成，感谢您的信任，欢迎评价。".formatted(value(p, "orderNo"));
            case ORDER_CANCELLED -> "订单 %s 已取消，原因：%s。".formatted(
                    value(p, "orderNo"), value(p, "reason"));
            case AUDIT_RESULT -> "您的陪诊员资质申请%s。%s".formatted(
                    value(p, "result"), emptyIfMissing(p, "rejectReason"));
            case MEDICATION_REMIND -> "%s 的「%s」在 %s 未确认服用，请及时关注。".formatted(
                    value(p, "elderName"), value(p, "medicineName"), value(p, "planTime"));
            case COMPLAINT_SUBMITTED -> "%s 就订单 %s 提交了投诉（%s），请及时处理。".formatted(
                    value(p, "submitterName"), value(p, "orderNo"), value(p, "typeLabel"));
            case COMPLAINT_HANDLED -> "您提交的投诉（%s）已处理完成：%s".formatted(
                    value(p, "orderNo"), value(p, "handleResult"));
            case SYSTEM_NOTICE -> value(p, "content");
        };
    }

    /**
     * 消息点击后的前端跳转路径。
     *
     * <p>路径由后端给出而不是前端按 {@code bizType} 拼：路由结构是前端的事，
     * 但「这条消息指向哪笔订单」是后端才知道的事实。
     * 让前端拿 {@code bizType + bizId} 自己拼，等于把两边的映射表各维护一份。</p>
     *
     * @param bizId 关联业务主键，可为 {@code null}
     */
    public static String linkUrl(MessageType type, Long bizId) {
        if (bizId == null) {
            return null;
        }
        return switch (type) {
            case ORDER_CREATED, ORDER_ACCEPTED, ORDER_PROGRESS, ORDER_COMPLETED, ORDER_CANCELLED ->
                    "/family?orderId=" + bizId;
            case AUDIT_RESULT -> "/profile/companion";
            case MEDICATION_REMIND -> "/family?elderId=" + bizId;
            case COMPLAINT_SUBMITTED -> "/admin?complaintId=" + bizId;
            case COMPLAINT_HANDLED -> "/complaint/" + bizId;
            case SYSTEM_NOTICE -> null;
        };
    }

    /** 消息类型是否属于某个业务域，供按域筛选时使用 */
    public static boolean inBiz(String type, String bizType) {
        MessageType messageType = MessageType.of(type);
        return messageType != null && messageType.getBizType().equals(bizType)
                || MessageBizType.SYSTEM.equals(bizType) && MessageType.SYSTEM_NOTICE.name().equals(type);
    }

    /** 按数据库列宽截断 */
    public static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private static String value(Map<String, ?> params, String key) {
        Object raw = params.get(key);
        if (raw == null) {
            return UNKNOWN;
        }
        String text = String.valueOf(raw).trim();
        return text.isEmpty() ? UNKNOWN : text;
    }

    /** 可选占位符：缺失时返回空串（例如驳回原因在「通过」时本就不该出现） */
    private static String emptyIfMissing(Map<String, ?> params, String key) {
        Object raw = params.get(key);
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
