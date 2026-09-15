package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.time.LocalDateTime;

/**
 * SysUser —— 对应表 {@code sys_user}。
 *
 * <p>用户主表（四类角色共用）</p>
 *
 * <p>主键与审计字段（id / createTime / updateTime / deleted）继承自 {@link BaseEntity}。</p>
 *
 * <p>⚠️ 本类为持久化实体，禁止直接作为接口返回值；对外统一转 VO，
 * 避免密码、身份证号、完整手机号等敏感字段外泄。</p>
 *
 * @since M1
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    /**
     * 登录用户名，4-20 位字母数字下划线
     */
    @Schema(description = "登录用户名，4-20 位字母数字下划线")
    private String username;

    /**
     * 登录密码，BCrypt 哈希（$2a$ 开头），禁止明文/可逆加密
     */
    @Schema(description = "登录密码，BCrypt 哈希（$2a$ 开头），禁止明文/可逆加密")
    private String password;

    /**
     * 昵称（如「张大爷」「李阿姨」），面向老人的称呼
     */
    @Schema(description = "昵称（如「张大爷」「李阿姨」），面向老人的称呼")
    private String nickname;

    /**
     * 真实姓名，陪诊员/管理员使用，老人与家属可空
     */
    @Schema(description = "真实姓名，陪诊员/管理员使用，老人与家属可空")
    private String realName;

    /**
     * 手机号，唯一，接口返回时须脱敏
     */
    @Schema(description = "手机号，唯一，接口返回时须脱敏")
    private String phone;

    /**
     * 头像 URL
     */
    @Schema(description = "头像 URL")
    private String avatar;

    /**
     * 角色：ELDER-老年患者 / FAMILY-家属 / COMPANION-陪诊员 / ADMIN-管理员
     */
    @Schema(description = "角色：ELDER-老年患者 / FAMILY-家属 / COMPANION-陪诊员 / ADMIN-管理员")
    private String role;

    /**
     * 账号状态：NORMAL-正常 / DISABLED-已封禁
     */
    @Schema(description = "账号状态：NORMAL-正常 / DISABLED-已封禁")
    private String status;

    /**
     * 是否需强制改密：0-否 1-是（管理员重置密码后置 1）
     */
    @Schema(description = "是否需强制改密：0-否 1-是（管理员重置密码后置 1）")
    private Integer needChangePassword;

    /**
     * 最后登录时间
     */
    @Schema(description = "最后登录时间")
    private LocalDateTime lastLoginTime;

    /**
     * 最后登录 IP
     */
    @Schema(description = "最后登录 IP")
    private String lastLoginIp;

    /**
     * 备注（封禁原因等）
     */
    @Schema(description = "备注（封禁原因等）")
    private String remark;

}
