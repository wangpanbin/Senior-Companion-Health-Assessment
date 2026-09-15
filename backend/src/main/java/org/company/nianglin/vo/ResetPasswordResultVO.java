package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 重置密码结果（{@code docs/api/08-admin.md} §7）。
 *
 * <p>返回默认密码是为了让管理员在电话里直接念给用户 —— 这是实际的业务场景
 * （老人家属来电说「密码忘了」）。默认密码是固定值、且被强制要求首次登录后修改，
 * 因此在这里返回它不构成新的泄露面。</p>
 *
 * <p><b>绝不返回原密码</b>：库里是 BCrypt 哈希，本就不可逆。</p>
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

    @Schema(description = "重置后的默认密码，需提醒用户首次登录后立即修改", example = "Nl@123456")
    private String defaultPassword;

    @Schema(description = "是否已置为需强制改密", example = "true")
    private Boolean needChangePassword;

    public static ResetPasswordResultVO of(Long userId, String defaultPassword) {
        return new ResetPasswordResultVO()
                .setUserId(userId)
                .setDefaultPassword(defaultPassword)
                .setNeedChangePassword(Boolean.TRUE);
    }
}
