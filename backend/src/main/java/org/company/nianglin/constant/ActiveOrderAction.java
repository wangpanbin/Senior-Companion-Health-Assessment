package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 封禁陪诊员时其在手订单的处置方式（closure-2026-11 · P0-4）。
 *
 * <p>管理员封禁一个正在服务中的陪诊员之后，他名下的「已接单 / 服务中」订单
 * 会变成无主订单：陪诊员打不了卡也完不成，家属无法取消（取消只允许待接单），
 * 履约风险中心也看不到（只扫待接单）。唯一的出路是管理员手动仲裁强制终态 ——
 * 而后台没有任何告警，必须靠人发现。</p>
 *
 * <p>因此封禁陪诊员时<b>必须先处置在手订单</b>，二选一：</p>
 *
 * <ul>
 *   <li>{@link #RELEASE}：放回大厅（订单继续，由别人接手）；</li>
 *   <li>{@link #CANCEL}：强制取消（并通知家属）。</li>
 * </ul>
 *
 * <p>不指定（{@code null}）时封禁接口回
 * {@code 2012 COMPANION_HAS_ACTIVE_ORDER}，前端据此弹处置选择框。</p>
 *
 * @author 银龄伴诊团队
 * @since closure-2026-11
 */
@Getter
public enum ActiveOrderAction {

    /** 放回大厅：状态回 PENDING、清空接单人，订单继续由其他人接手 */
    RELEASE("放回大厅"),

    /** 强制取消：走 forceTerminal → CANCELLED，并通知家属 */
    CANCEL("强制取消"),
    ;

    /** 中文展示名 */
    private final String label;

    ActiveOrderAction(String label) {
        this.label = label;
    }

    /** 按枚举名反查（忽略大小写），非法值返回 {@code null} 由调用方决定抛什么错 */
    public static ActiveOrderAction of(String name) {
        if (name == null) {
            return null;
        }
        for (ActiveOrderAction action : values()) {
            if (action.name().equalsIgnoreCase(name)) {
                return action;
            }
        }
        return null;
    }

    /** 中文展示名；入参为空或非法时原样返回，避免 VO 里出现 {@code null} */
    public static String labelOf(String name) {
        ActiveOrderAction action = of(name);
        return action == null ? name : action.getLabel();
    }
}
