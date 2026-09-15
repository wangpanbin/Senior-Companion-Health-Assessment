package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 更新当前用户资料入参。
 *
 * <p>对应 {@code PUT /api/user/profile}（{@code docs/api/02-elder-family.md} §2）。</p>
 *
 * <p><b>这里刻意只有两个字段</b>：手机号走绑定流程（涉及唯一性与旧号验证），
 * 密码走 {@code /api/auth/password}（要校验原密码并让旧令牌失效）。
 * 如果把 {@code phone} 塞进这个「改资料」接口，等于给了一条绕过验证换绑手机号的路。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Data
@Accessors(chain = true)
@Schema(description = "更新当前用户资料入参")
public class ProfileUpdateDTO {

    @Schema(description = "昵称，2-20 字符；为空表示不修改", example = "张大爷")
    @Size(min = 2, max = 20, message = "昵称长度需在 2-20 个字符之间")
    private String nickname;

    @Schema(description = "头像地址；为空表示不修改", example = "/uploads/202609/avatar.png")
    @Size(max = 255, message = "头像地址过长")
    private String avatar;
}
