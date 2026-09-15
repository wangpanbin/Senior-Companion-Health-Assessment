package org.company.nianglin.constant;

import lombok.Getter;

/**
 * 陪诊打卡节点。
 *
 * <p>对应 {@code docs/api/04-companion-execution.md} §一「打卡节点枚举」，
 * 与 {@code order_checkin.node} / {@code order_checkin.node_sort} 一一对应。</p>
 *
 * <pre>
 *   出发(1) → 到院(2) → 就诊中(3) → 取药(4) → 离院(5) → 完成(6)
 *        ↑ 可跳过（如本次不需要取药）
 *        ✗ 不可回退（不能先打「取药」再打「到院」）
 * </pre>
 *
 * <p><b>为什么顺序值单独存一列而不是靠 {@code node} 字符串比较</b>：
 * 枚举名是按业务叙述排的，字母序（ARRIVE &lt; DEPART &lt; FINISH …）与业务顺序毫无关系。
 * 每次校验都靠 {@code ordinal()} 也行，但一旦有人为了可读性调整枚举声明顺序，
 * 历史数据的 {@code node_sort} 与新的 {@code ordinal()} 就会错位 ——
 * 存进库里的是快照，不随代码变动，这才是可靠的做法。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@Getter
public enum CheckinNode {

    DEPART("出发", 1),
    ARRIVE("到院", 2),
    IN_CONSULT("就诊中", 3),
    TAKE_MEDICINE("取药", 4),
    LEAVE("离院", 5),
    FINISH("完成", 6),
    ;

    /** 中文展示名 */
    private final String label;

    /** 节点顺序值 1-6，落库到 {@code node_sort} */
    private final int sort;

    CheckinNode(String label, int sort) {
        this.label = label;
        this.sort = sort;
    }

    /** 按枚举名反查（忽略大小写），非法值返回 {@code null} 由调用方决定抛什么错 */
    public static CheckinNode of(String name) {
        if (name == null) {
            return null;
        }
        for (CheckinNode node : values()) {
            if (node.name().equalsIgnoreCase(name)) {
                return node;
            }
        }
        return null;
    }

    /** 按顺序值反查，找不到返回 {@code null} */
    public static CheckinNode ofSort(Integer sort) {
        if (sort == null) {
            return null;
        }
        for (CheckinNode node : values()) {
            if (node.sort == sort) {
                return node;
            }
        }
        return null;
    }

    /** 下一个节点，已是末节点返回 {@code null} */
    public CheckinNode next() {
        return ofSort(this.sort + 1);
    }

    /** 节点总数，用于算进度百分比 */
    public static int total() {
        return values().length;
    }

    /**
     * 中文展示名；入参为空或非法时原样返回。
     *
     * <p>展示层不该因为库里有一条脏节点值就整个时间线 500。</p>
     */
    public static String labelOf(String name) {
        CheckinNode node = of(name);
        return node == null ? name : node.getLabel();
    }
}
