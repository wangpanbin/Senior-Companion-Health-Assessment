package org.company.nianglin.vo;

import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.entity.SysUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 管理端用户列表的脱敏红线。
 *
 * <p><b>为什么单独锁一条</b>：此前 {@code AdminUserVO.realName} 直接透传
 * {@code user.getRealName()}，而紧挨着的手机号却是脱敏的 —— 同屏两列口径不一致。
 * 这不是「已记录的例外」，而是漏改：{@code AGENTS.md} §0.1 明确
 * 「{@code /api/admin/**} 一律脱敏」。</p>
 *
 * <p>⚠️ <b>本类不覆盖</b> {@code AuditApplicationVO.realName} ——
 * 资质审核要核对证件原件，那是 {@code docs/api/08-admin.md} 已明写的场景，
 * 保持明文是刻意的。</p>
 *
 * @author 银龄伴诊团队
 */
@DisplayName("管理端用户列表：姓名与手机号都必须脱敏")
class AdminUserVOMaskTest {

    @Test
    @DisplayName("realName 必须脱敏（李四 → 李*），与手机号同口径")
    void realNameShouldBeMasked() {
        AdminUserVO vo = AdminUserVO.of(user("李四"), 3);

        assertEquals("李*", vo.getRealName());
        assertFalse("李四".equals(vo.getRealName()),
                "管理端用户列表不得返回未脱敏的真实姓名 —— /api/admin/** 一律脱敏");
    }

    @Test
    @DisplayName("两字姓名也必须遮蔽 —— 「静默不脱敏」在隐私场景下不可接受")
    void twoCharNameShouldStillBeMasked() {
        AdminUserVO vo = AdminUserVO.of(user("李四"), 0);

        assertEquals("李*", vo.getRealName(),
                "两字姓名若原样返回，等于完全没脱敏（早期 Hutool hide 在 start==end 时的坑）");
    }

    @Test
    @DisplayName("三字姓名保留首尾：张三丰 → 张*丰")
    void threeCharNameShouldKeepFirstAndLast() {
        AdminUserVO vo = AdminUserVO.of(user("张三丰"), 0);

        assertEquals("张*丰", vo.getRealName());
    }

    @Test
    @DisplayName("手机号同口径脱敏")
    void phoneShouldBeMasked() {
        AdminUserVO vo = AdminUserVO.of(user("李四"), 0);

        assertEquals("139****6677", vo.getPhone());
    }

    @Test
    @DisplayName("姓名为空时不抛异常，且返回 null（而不是空串）")
    void nullRealNameShouldNotThrow() {
        SysUser user = user(null);

        AdminUserVO vo = AdminUserVO.of(user, 0);

        assertNull(vo.getRealName());
    }

    private SysUser user(String realName) {
        SysUser user = new SysUser();
        user.setId(10099L);
        user.setUsername("lisi");
        user.setNickname("李阿姨");
        user.setRealName(realName);
        user.setPhone("13900006677");
        user.setRole(RoleConstants.FAMILY);
        user.setStatus("NORMAL");
        return user;
    }
}
