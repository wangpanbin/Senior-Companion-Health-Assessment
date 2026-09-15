package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.ValidationPatterns;

/**
 * 家属绑定老人账号入参。
 *
 * <p>对应 {@code POST /api/user/elder/bind}（{@code docs/api/02-elder-family.md} §11）。</p>
 *
 * <p><b>为什么绑的不是「档案」而是「账号」</b>：这个接口处理的是「老人自己（或别人帮忙）
 * 已经注册过账号」的情况，家属凭手机号认领。而 {@code POST /api/user/elder} 处理的是
 * 「老人压根没有账号、家属代为建档」—— 后者会自动把新档案绑给建档人，
 * 两者最终都落在 {@code family_elder_relation} 上。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Data
@Accessors(chain = true)
@Schema(description = "家属绑定老人账号入参")
public class ElderBindDTO {

    @Schema(description = "绑定方式：PHONE-按手机号（一期仅支持）/ INVITE_CODE-按邀请码（未开放）",
            example = "PHONE", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "绑定方式不能为空")
    @Pattern(regexp = ValidationPatterns.BIND_TYPE, message = "绑定方式只能是 PHONE 或 INVITE_CODE")
    private String bindType;

    @Schema(description = "手机号或邀请码", example = "13911112222",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "绑定凭证不能为空")
    @Size(max = 50, message = "绑定凭证过长")
    private String bindValue;

    @Schema(description = "与老人关系：SON-儿子 / DAUGHTER-女儿 / RELATIVE-亲属 / OTHER-其他",
            example = "SON", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "与老人关系不能为空")
    @Pattern(regexp = ValidationPatterns.RELATION,
            message = "与老人关系只能是 SON / DAUGHTER / RELATIVE / OTHER")
    private String relation;
}
