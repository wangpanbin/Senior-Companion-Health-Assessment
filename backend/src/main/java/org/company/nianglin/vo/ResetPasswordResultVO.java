package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 重置密码结果（{@code docs/api/08-admin.md} §7）。
 *
 * <p><b>刻意不回传默认口令</b>：默认口令由部署配置
 * （{@code nianglin.security.default-password} / 环境变量 {@code DEFAULT_RESET_PASSWORD}）决定，
 * 是「同一套部署内所有被重置账号共用」的固定串。把它放进管理员接口的响应体（以及由此产生的
 * 访问日志 / 前端调试记录）等于把该口令二次扩散；而它本就是固定值，管理员按既定初始口令
 * 告知用户即可，不需要接口回传。</p>
 *
 * <p>因此本 VO 只回传「重置成功 + 已被置为需强制改密」这两个事实。
 * <b>绝不返回原密码</b>：库里是 BCrypt 哈希，本就不可逆。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@Accessors(chain = true)
@Schema(description = "重置密码结果")
public class ResetPasswordResultVO {

    @Schema(description = "用户 ID", example = "10099")
    private Long userId;

    @Schema(description = "是否已置为需强制改密（首次登录必须改密）", example = "true")
    private Boolean needChangePassword;

    public static ResetPasswordResultVO of(Long userId) {
        return new ResetPasswordResultVO()
                .setUserId(userId)
                .setNeedChangePassword(Boolean.TRUE);
    }
}
