package org.company.nianglin.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 打卡节点枚举单测（M5 「可跳过、不可回退」的基础）。
 *
 * <p>这里锁三件事：顺序值连续无缺口、枚举名与顺序值一一对应、
 * 非法输入返回 {@code null} 而不是抛异常（展示层不该因为一条脏数据 500）。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@DisplayName("打卡节点：顺序值 / 反查 / 展示名")
class CheckinNodeTest {

    @Test
    @DisplayName("顺序值从 1 起连续递增，共 6 个节点")
    void sortValuesAreContiguous() {
        assertEquals(6, CheckinNode.total());
        int expected = 1;
        for (CheckinNode node : CheckinNode.values()) {
            assertEquals(expected++, node.getSort(), node.name() + " 的顺序值应为 " + (expected - 1));
        }
    }

    @Test
    @DisplayName("按名字反查忽略大小写")
    void ofIgnoresCase() {
        assertEquals(CheckinNode.ARRIVE, CheckinNode.of("arrive"));
        assertEquals(CheckinNode.ARRIVE, CheckinNode.of("ARRIVE"));
        assertEquals(CheckinNode.TAKE_MEDICINE, CheckinNode.of("take_medicine"));
    }

    @Test
    @DisplayName("非法名字返回 null，不抛异常")
    void ofReturnsNullForUnknown() {
        assertNull(CheckinNode.of("WAITING"));
        assertNull(CheckinNode.of(null));
    }

    @Test
    @DisplayName("按顺序值反查；越界返回 null")
    void ofSort() {
        assertEquals(CheckinNode.DEPART, CheckinNode.ofSort(1));
        assertEquals(CheckinNode.FINISH, CheckinNode.ofSort(6));
        assertNull(CheckinNode.ofSort(0));
        assertNull(CheckinNode.ofSort(7));
        assertNull(CheckinNode.ofSort(null));
    }

    @Test
    @DisplayName("next() 沿业务顺序前进，末节点返回 null")
    void nextFollowsBusinessOrderNotAlphabet() {
        assertEquals(CheckinNode.ARRIVE, CheckinNode.DEPART.next());
        assertEquals(CheckinNode.IN_CONSULT, CheckinNode.ARRIVE.next());
        // 字母序里 ARRIVE < DEPART < FINISH，业务顺序却完全相反 —— 这正是不能靠字符串比较的原因
        assertNotEquals(CheckinNode.of("FINISH"), CheckinNode.DEPART.next());
        assertNull(CheckinNode.FINISH.next());
    }

    @Test
    @DisplayName("中文展示名；非法值原样返回，空值返回 null")
    void labelOf() {
        assertEquals("出发", CheckinNode.labelOf("DEPART"));
        assertEquals("取药", CheckinNode.labelOf("TAKE_MEDICINE"));
        assertEquals("SOMETHING_ELSE", CheckinNode.labelOf("SOMETHING_ELSE"));
        assertNull(CheckinNode.labelOf(null));
    }
}
