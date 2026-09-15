package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.util.MaskUtil;

import java.time.LocalDateTime;

/**
 * 用户信息（对外唯一出口）。
 *
 * <p><b>为什么必须有这个类</b>：{@code SysUser} 上有 {@code password} 字段，
 * 一旦某天有人图省事直接 {@code return user}，BCrypt 哈希就随响应体出网了。
 * 强制经本类转换，等于给敏感字段加了一道编译期屏障。</p>
 *
 * <p>字段清单严格对齐 {@code docs/api/01-auth-user.md} §一：
 * 不含密码、身份证号、完整手机号。</p>
 *
 * @author 银龄伴诊团队
 */
@Data
@Accessors(chain = true)
@Schema(description = "用户信息")
public class UserInfoVO {

    @Schema(description = "用户 ID", example = "10023")
    private Long id;

    @Schema(description = "登录用户名", example = "family001")
    private String username;

    @Schema(description = "昵称", example = "张三")
    private String nickname;

    @Schema(description = "角色枚举名", example = "FAMILY")
    private String role;

    @Schema(description = "角色中文名", example = "家属")
    private String roleLabel;

    @Schema(description = "脱敏手机号", example = "138****8888")
    private String phone;

    @Schema(description = "头像 URL，可为空")
    private String avatar;

    @Schema(description = "账号状态", example = "NORMAL")
    private String status;

    @Schema(description = "注册时间", example = "2026-09-01 10:20:30")
    private LocalDateTime createTime;

    /**
     * 由实体转换而来。
     *
     * <p>手机号在这里就<b>已经脱敏</b>，调用方拿到的 VO 无论如何都无法泄露完整号码 ——
     * 比起「转换时记得调一下 MaskUtil」的约定，把脱敏焊死在工厂方法里更可靠。</p>
     *
     * @param user 用户实体，可为 {@code null}
     * @return VO，入参为 {@code null} 时返回 {@code null}
     */
    public static UserInfoVO of(SysUser user) {
        if (user == null) {
            return null;
        }
        RoleConstants.Role role = RoleConstants.Role.of(user.getRole());
        return new UserInfoVO()
                .setId(user.getId())
                .setUsername(user.getUsername())
                .setNickname(user.getNickname())
                .setRole(user.getRole())
                .setRoleLabel(role == null ? user.getRole() : role.getLabel())
                .setPhone(MaskUtil.phone(user.getPhone()))
                .setAvatar(user.getAvatar())
                .setStatus(user.getStatus())
                .setCreateTime(user.getCreateTime());
    }
}
