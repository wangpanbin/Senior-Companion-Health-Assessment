package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.time.LocalDateTime;

/**
 * SysLoginLog —— 对应表 {@code sys_login_log}。
 *
 * <p>登录日志表（只增不改）</p>
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
@TableName("sys_login_log")
public class SysLoginLog extends BaseEntity {

    /**
     * 用户 ID，账号不存在时为 NULL
     */
    @Schema(description = "用户 ID，账号不存在时为 NULL")
    private Long userId;

    /**
     * 登录时输入的用户名
     */
    @Schema(description = "登录时输入的用户名")
    private String username;

    /**
     * 类型：LOGIN-登录 / LOGOUT-登出 / REFRESH-刷新令牌
     */
    @Schema(description = "类型：LOGIN-登录 / LOGOUT-登出 / REFRESH-刷新令牌")
    private String loginType;

    /**
     * 结果：SUCCESS-成功 / FAIL-失败
     */
    @Schema(description = "结果：SUCCESS-成功 / FAIL-失败")
    private String status;

    /**
     * 失败原因（账号不存在/密码错误/验证码错误/账号封禁/锁定）
     */
    @Schema(description = "失败原因（账号不存在/密码错误/验证码错误/账号封禁/锁定）")
    private String failReason;

    /**
     * 客户端 IP
     */
    @Schema(description = "客户端 IP")
    private String ip;

    /**
     * 浏览器 UA
     */
    @Schema(description = "浏览器 UA")
    private String userAgent;

    /**
     * 发生时间
     */
    @Schema(description = "发生时间")
    private LocalDateTime loginTime;

}
