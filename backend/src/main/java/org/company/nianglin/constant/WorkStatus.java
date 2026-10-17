package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 陪诊员接单状态（{@code companion_profile.work_status}）。
 *
 * <p>与资质 {@link AuditStatus} 是<b>两个正交的开关</b>：
 * 资质决定「有没有资格接单」，接单状态决定「现在想不想接单」。
 * 一个已通过的陪诊员可以把自己置为 {@link #REST} 去休息，
 * 但他不会因为休息而丢掉资质；反过来，待审核的人即使把状态设成
 * {@link #AVAILABLE} 也接不了单 —— 判定顺序永远是「先看资质，再看状态」。</p>
 *
 * <h3>V3.1 扩展：{@link #OFFLINE}</h3>
 *
 * <p>新增「离线」以区分两种「不接单」：{@link #REST} 是<b>主动</b>暂停
 * （我今天排满了，别推给我），{@link #OFFLINE} 是<b>被动</b>离开
 * （我现在没在上线）。两者在接单资格上完全一致，都不接受新订单。</p>
 *
 * <p><b>一期不要为 {@link #OFFLINE} 加在线状态展示。</b>
 * 本枚举目前只是「能不能接新单」的单值开关，平台尚不做在线状态展示，
 * 也不会按在线状态路由消息 —— 早期把「离线」理解成「不推送实时消息」并据此
 * 改推送链路，是一次没有产品依据的行为变更。</p>
 *
 * <p><b>接单状态只影响新订单的匹配，不影响已接订单。</b>
 * 陪诊员切到休息或离线后，他手上 {@code ACCEPTED} / {@code IN_SERVICE}
 * 的订单继续按订单状态机推进，不会被中断、也不会被取消 ——
 * 这是「自主控制接单」不能变成「自主放弃在手服务」的底线。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Getter
public enum WorkStatus {

    /** 接单中：进入订单大厅并可接单 */
    AVAILABLE("可接单"),

    /** 休息中：主动暂停接单，不进入新单匹配 */
    REST("休息中"),

    /** 离线：当前不在线，不接受新订单（一期仅作状态标记） */
    OFFLINE("离线"),
    ;

    private final String label;

    WorkStatus(String label) {
        this.label = label;
    }

    /**
     * 是否接受新订单。
     *
     * <p>这是「接单资格」的<b>唯一</b>判定入口：所有需要「这个陪诊员现在能不能接单」
     * 的地方都调它，不要写 {@code "AVAILABLE".equals(status)} 这种裸字符串比较 ——
     * 一旦将来再加一个「忙但可接」的状态，散落的字符串比较会漏掉它。</p>
     *
     * <p>注意本方法<b>只回答接单状态这一个维度</b>。完整判定还需要先过资质
     * （{@code auditStatus == APPROVED}）与健康证有效期，顺序是「先资质、再状态」。</p>
     */
    public boolean isAccepting() {
        return this == AVAILABLE;
    }

    public static WorkStatus of(String name) {
        if (name == null) {
            return null;
        }
        for (WorkStatus s : values()) {
            if (s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }

    public static String labelOf(String name) {
        WorkStatus s = of(name);
        return s == null ? name : s.getLabel();
    }
}
