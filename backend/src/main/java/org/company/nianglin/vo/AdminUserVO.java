package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.AccountStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.entity.SysUser;
import org.company.nianglin.util.MaskUtil;

import java.time.LocalDateTime;

/**
 * 用户管理列表项（{@code docs/api/08-admin.md} §一 {@code AdminUserVO} / §4）。
 *
 * <h3>手机号必须脱敏（验收项）</h3>
 *
 * <p>管理端列表一屏能看到几十个用户，而管理员真正需要的只是「认出是哪个人」。
 * 完整手机号在这里没有任何增量价值 —— 需要联系用户时，
 * 他看的应该是详情的联系方式，那是一个单独的动作，也更值得被记录。</p>
 *
 * <h3>密码字段永不出现</h3>
 *
 * <p>本 VO 由 {@code SysUser} 装配，而 {@code SysUser} 里有 {@code password}
 * （BCrypt 哈希）。这里刻意逐字段赋值而不是 {@code BeanUtils.copyProperties}：
 * 拷贝式装配会在实体新增一个敏感字段时<b>自动把它带出去</b>，
 * 而逐字段装配的默认行为是「不返回」，加错的风险方向是相反的。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@Accessors(chain = true)
@Schema(description = "用户管理列表项")
public class AdminUserVO {

    @Schema(description = "用户 ID", example = "10099")
    private Long id;

    @Schema(description = "用户名", example = "lisi")
    private String username;

    @Schema(description = "昵称", example = "李阿姨")
    private String nickname;

    @Schema(description = "真实姓名", example = "李四")
    private String realName;

    @Schema(description = "手机号（脱敏）", example = "139****6677")
    private String phone;

    @Schema(description = "角色：ELDER / FAMILY / COMPANION / ADMIN", example = "COMPANION")
    private String role;

    @Schema(description = "角色中文名", example = "陪诊员")
    private String roleLabel;

    @Schema(description = "账号状态：NORMAL / DISABLED", example = "NORMAL")
    private String status;

    @Schema(description = "账号状态中文名", example = "正常")
    private String statusLabel;

    @Schema(description = "关联订单数（家属为其下单数 / 陪诊员接单数 / 老人作为就诊人的订单数之和）",
            example = "12")
    private Integer orderCount;

    @Schema(description = "注册时间", example = "2026-09-10 09:00:00")
    private LocalDateTime createTime;

    @Schema(description = "最后登录时间", example = "2026-09-20 18:22:10")
    private LocalDateTime lastLoginTime;

    /**
     * 装配。
     *
     * @param user       用户实体
     * @param orderCount 关联订单数，可为 {@code null}（查不到时按 0 返回）
     */
    public static AdminUserVO of(SysUser user, Integer orderCount) {
        if (user == null) {
            return null;
        }
        return new AdminUserVO()
                .setId(user.getId())
                .setUsername(user.getUsername())
                .setNickname(user.getNickname())
                .setRealName(user.getRealName())
                .setPhone(MaskUtil.phone(user.getPhone()))
                .setRole(user.getRole())
                .setRoleLabel(RoleConstants.Role.labelOf(user.getRole()))
                .setStatus(user.getStatus())
                .setStatusLabel(statusLabel(user.getStatus()))
                .setOrderCount(orderCount == null ? 0 : orderCount)
                .setCreateTime(user.getCreateTime())
                .setLastLoginTime(user.getLastLoginTime());
    }

    /** 账号状态中文名；非法值原样返回，避免列表因为一条脏数据整体 500 */
    private static String statusLabel(String status) {
        if (AccountStatus.NORMAL.equals(status)) {
            return "正常";
        }
        if (AccountStatus.DISABLED.equals(status)) {
            return "已封禁";
        }
        return status;
    }
}
