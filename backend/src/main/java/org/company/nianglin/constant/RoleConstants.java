package org.company.nianglin.constant;

import lombok.Getter;

import java.util.Arrays;

/**
 * 用户角色常量。
 *
 * <p>与 Spring Security 的 authority 保持一致，形如 {@code ROLE_FAMILY}。
 * 接口鉴权时使用 {@code @PreAuthorize("hasRole('FAMILY')")}。</p>
 *
 * @author 银龄伴诊团队
 */
public final class RoleConstants {

    private RoleConstants() {
    }

    /** 老年患者：**默认只读**，写操作一律由家属代操作（服务端必须拦截） */
    public static final String ELDER = "ELDER";

    /** 家属：下单、代老人操作、查看进度 */
    public static final String FAMILY = "FAMILY";

    /** 陪诊员：需资质审核通过后才能接单、打卡 */
    public static final String COMPANION = "COMPANION";

    /** 管理员：资质审核、用户封禁、订单纠纷、数据统计 */
    public static final String ADMIN = "ADMIN";

    /** Spring Security 权限前缀 */
    public static final String ROLE_PREFIX = "ROLE_";

    /** 全部角色 */
    public static final String[] ALL = {ELDER, FAMILY, COMPANION, ADMIN};

    /** 仅可写角色（老人账号被排除在外） */
    public static final String[] WRITABLE = {FAMILY, COMPANION, ADMIN};

    /**
     * 角色枚举（供业务层做类型安全判断与展示）
     */
    @Getter
    public enum Role {

        ELDER("老年患者"),
        FAMILY("家属"),
        COMPANION("陪诊员"),
        ADMIN("管理员"),
        ;

        private final String label;

        Role(String label) {
            this.label = label;
        }

        public static Role of(String code) {
            return Arrays.stream(values())
                    .filter(r -> r.name().equalsIgnoreCase(code))
                    .findFirst()
                    .orElse(null);
        }

        /** Spring Security 权限名 */
        public String authority() {
            return ROLE_PREFIX + name();
        }
    }
}
