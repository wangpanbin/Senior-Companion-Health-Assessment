package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 重置密码结果（{@code docs/api/08-admin.md} §7）。
 *
 * <p><b>本 VO 永远不含任何口令</b>。这是仓库自己的安全验收判据
 * （{@code docs/security/安全评估报告-2026-10-18.md} §8.2 P0-3：
 * 「接口响应<b>不含口令字段</b>」；§10.2 F-04 落地明细：
 * 「{@code ResetPasswordResultVO} 删除 {@code defaultPassword} 字段」）。</p>
 *
 * <p>⚠️ 2026-10-25 的交互修复一度想加一个 {@code temporaryPassword} 字段，
 * 好让管理员把新口令转交给用户 —— 那是<b>把已关闭的 F-04 重新打开</b>，已回退。
 * 正确的做法是：口令由管理员在请求体里<b>输入</b>（{@code ResetPasswordDTO#newPassword}），
 * 前端自己记得他刚输入了什么，转交动作完全在浏览器里完成，
 * 服务端从头到尾不回传、也不记录任何口令。</p>
 *
 * <p>默认口令（{@code nianglin.security.default-password}）由部署配置经环境变量注入，
 * 是「同一套部署内所有被重置账号共用」的固定串；它<b>绝不</b>随响应下发 ——
 * 放进响应体等于二次扩散。</p>
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

    @Schema(description = "是否已置为需强制改密（首次登录必须改密）", example = "true")
    private Boolean needChangePassword;

    public static ResetPasswordResultVO of(Long userId) {
        return new ResetPasswordResultVO()
                .setUserId(userId)
                .setNeedChangePassword(Boolean.TRUE);
    }
}
