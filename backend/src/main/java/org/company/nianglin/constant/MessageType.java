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
    COMPLAINT_SUBMITTED("新投诉待处理", MessageBizType.COMPLAINT),
    COMPLAINT_HANDLED("投诉处理结果", MessageBizType.COMPLAINT),
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
