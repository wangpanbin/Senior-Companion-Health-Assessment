package org.company.nianglin.constant;

/**
 * 账号状态常量（对应 {@code sys_user.status}）。
 *
 * <p>数据库存大写英文枚举名，禁止存中文（{@code V1__init_schema.sql} 约定 7）。</p>
 *
 * @author 银龄伴诊团队
 */
public final class AccountStatus {

    private AccountStatus() {
    }

    /** 正常 */
    public static final String NORMAL = "NORMAL";

    /** 已封禁：即使密码正确也拒绝登录，且已签发令牌立即失效 */
    public static final String DISABLED = "DISABLED";
}
