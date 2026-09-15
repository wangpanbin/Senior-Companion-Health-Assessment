package org.company.nianglin.constant;

/**
 * 站内信关联的业务类型（{@code internal_message.biz_type}）。
 *
 * <p>与 {@link MessageType} 的区别：{@code type} 描述「这条消息长什么样」（决定标题模板），
 * {@code bizType} 描述「它属于哪个业务域」（决定点击后跳到哪个模块）。
 * 九个消息类型只落在五个业务域上 —— 订单类消息最多。</p>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
public final class MessageBizType {

    private MessageBizType() {
    }

    /** 订单：下单 / 接单 / 进度 / 完成 / 取消 */
    public static final String ORDER = "ORDER";

    /** 资质审核 */
    public static final String AUDIT = "AUDIT";

    /** 用药提醒 */
    public static final String MEDICATION = "MEDICATION";

    /** 投诉处理 */
    public static final String COMPLAINT = "COMPLAINT";

    /** 系统公告 */
    public static final String SYSTEM = "SYSTEM";
}
