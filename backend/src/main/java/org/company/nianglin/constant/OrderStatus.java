package org.company.nianglin.constant;

import lombok.Getter;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 陪诊订单状态机。
 *
 * <p><b>这是本项目的核心业务约束，所有涉及订单状态的代码必须通过本枚举判断，禁止硬编码状态字符串。</b></p>
 *
 * <pre>
 *   待接单(PENDING) ──► 已接单(ACCEPTED) ──► 服务中(IN_SERVICE) ──► 已完成(COMPLETED) ──► 已评价(REVIEWED)
 *        │                    │                    │
 *        └────────────────────┴────────────────────┴──► 已取消(CANCELLED)   ← 仅管理员可强制进入
 * </pre>
 *
 * <p>铁律：</p>
 * <ol>
 *   <li><b>禁止跳级</b>：待接单不能直接变已完成</li>
 *   <li><b>禁止回退</b>：已接单不能退回待接单</li>
 *   <li>仅 ADMIN 可通过「纠纷处理」强制改变终态，且必须写操作日志</li>
 * </ol>
 *
 * <p>对应验收标准（plan.md · M4）：跳级调用必须返回 {@code 3002}，数据库状态不变。</p>
 *
 * @author 银龄伴诊团队
 */
@Getter
public enum OrderStatus {

    PENDING("待接单", 1),
    ACCEPTED("已接单", 2),
    IN_SERVICE("服务中", 3),
    COMPLETED("已完成", 4),
    REVIEWED("已评价", 5),
    CANCELLED("已取消", 9),
    ;

    /** 中文展示名 */
    private final String label;

    /** 排序权重，用于列表默认排序 */
    private final int sort;

    OrderStatus(String label, int sort) {
        this.label = label;
        this.sort = sort;
    }

    /**
     * 正向流转表：key 的下一状态只允许是 value 中的元素。
     *
     * <p>注意 {@link #CANCELLED} 不在任何正向流转中 —— 取消只能由管理员的纠纷处理触发。</p>
     */
    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
            PENDING, EnumSet.of(ACCEPTED),
            ACCEPTED, EnumSet.of(IN_SERVICE),
            IN_SERVICE, EnumSet.of(COMPLETED),
            COMPLETED, EnumSet.of(REVIEWED),
            REVIEWED, EnumSet.noneOf(OrderStatus.class),
            CANCELLED, EnumSet.noneOf(OrderStatus.class)
    );

    /** 是否为终态（不可再正向流转） */
    public boolean isTerminal() {
        return this == REVIEWED || this == CANCELLED;
    }

    /** 是否允许从当前状态流转到目标状态（正向流转） */
    public boolean canTransitTo(OrderStatus target) {
        if (target == null) {
            return false;
        }
        return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    /** 是否为管理员可强制进入的终态 */
    public boolean isAdminForceable() {
        return this == CANCELLED || this == COMPLETED;
    }

    /** 按 code 反查 */
    public static OrderStatus ofSort(int sort) {
        for (OrderStatus s : values()) {
            if (s.sort == sort) {
                return s;
            }
        }
        return null;
    }

    /** 按名称反查（忽略大小写） */
    public static OrderStatus of(String name) {
        if (name == null) {
            return null;
        }
        for (OrderStatus s : values()) {
            if (s.name().equalsIgnoreCase(name) || s.label.equals(name)) {
                return s;
            }
        }
        return null;
    }
}
