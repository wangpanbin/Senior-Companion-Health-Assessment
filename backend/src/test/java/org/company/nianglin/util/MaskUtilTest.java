package org.company.nianglin.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感信息脱敏单测。
 *
 * <p>对应合规红线：手机号脱敏、身份证号禁止明文、日志不打印身份证号。</p>
 *
 * @author 银龄伴诊团队
 */
@DisplayName("敏感信息脱敏")
class MaskUtilTest {

    @Test
    @DisplayName("手机号：保留前 3 后 4")
    void phoneShouldMaskMiddle() {
        assertEquals("138****8888", MaskUtil.phone("13812348888"));
        assertEquals("138****8888", MaskUtil.phone("138 1234 8888".replace(" ", "")));
        assertNull(MaskUtil.phone(null));
        assertEquals("", MaskUtil.phone(""));
    }

    @Test
    @DisplayName("身份证号：保留前 6 后 4")
    void idCardShouldMaskMiddle() {
        assertEquals("110101********4567", MaskUtil.idCard("110101199003074567"));
        assertTrue(MaskUtil.idCard("110101199003074567").contains("*"));
        assertFalse(MaskUtil.idCard("110101199003074567").contains("19900307"));
    }

    @Test
    @DisplayName("姓名：保留姓氏与末字")
    void nameShouldKeepFirstAndLast() {
        assertEquals("张*丰", MaskUtil.name("张三丰"));
        assertEquals("李*", MaskUtil.name("李四"));
        assertEquals("王", MaskUtil.name("王"));
        assertEquals("张**", MaskUtil.nameAll("张三丰"));
    }

    @Test
    @DisplayName("地址：门牌号用 *** 替代")
    void addressShouldHideHouseNumber() {
        assertEquals("海南省海口市美兰区人民大道***", MaskUtil.address("海南省海口市美兰区人民大道12号3栋501"));
        assertEquals("海南省海口市", MaskUtil.address("海南省海口市"));
    }

    @Test
    @DisplayName("短字符串不应导致越界异常")
    void shortValueShouldBeSafe() {
        assertEquals("1*", MaskUtil.phone("12"));
        assertEquals("1", MaskUtil.idCard("1"));
        assertNull(MaskUtil.generic(null, 1, 1));
    }

    @Test
    @DisplayName("通用脱敏：保留前 N 后 M")
    void genericShouldWork() {
        assertEquals("6222***********3445", MaskUtil.bankCard("6222020200112233445"));
        assertEquals("abc***xyz", MaskUtil.generic("abcdefxyz", 3, 3));
    }
}
