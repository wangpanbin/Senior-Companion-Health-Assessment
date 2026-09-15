package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.ValidationPatterns;

import java.util.List;

/**
 * 提交陪诊员资质申请入参。
 *
 * <p>对应 {@code POST /api/user/companion/apply}（{@code docs/api/02-elder-family.md} §3）。</p>
 *
 * <p><b>注意这个请求体会落库一份 AES 密文</b>：{@code idCard} 是 18 位明文进、密文存，
 * 因此 DTO 的 {@code toString()} 会被 Lombok 自动生成并可能被日志框架调用 ——
 * 相关 Service 里禁止打印整个 DTO，只打印 {@code applicantUserId} 这类无敏感信息的字段。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Data
@Accessors(chain = true)
@Schema(description = "提交陪诊员资质申请入参")
public class CompanionApplyDTO {

    @Schema(description = "真实姓名，2-20 字符", example = "李建军",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "真实姓名不能为空")
    @Size(min = 2, max = 20, message = "真实姓名长度需在 2-20 个字符之间")
    private String realName;

    @Schema(description = "身份证号，18 位（服务端 AES 加密存储，不回显）",
            example = "460101199001010011", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "身份证号不能为空")
    @Pattern(regexp = ValidationPatterns.ID_CARD, message = "身份证号格式不正确")
    private String idCard;

    @Schema(description = "服务区域", example = "海口市美兰区",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "服务区域不能为空")
    @Size(max = 100, message = "服务区域过长")
    private String serviceArea;

    @Schema(description = "可服务时段", example = "周一至周五 08:00-18:00",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "可服务时段不能为空")
    @Size(max = 100, message = "可服务时段过长")
    private String availableTime;

    /**
     * 证件材料。
     *
     * <p>{@code @Valid} 加在集合字段上即可级联校验每个元素 —— 少了它
     * {@link CertificateItem} 里的 {@code @NotBlank} 全部失效，
     * 会出现「提交了 3 个空证件」也能通过校验的情况。空集合由 {@code @NotEmpty} 拦住。</p>
     */
    @Schema(description = "证件材料，至少 1 项", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "请至少上传 1 项证件材料")
    @Valid
    private List<CertificateItem> certificates;

    @Schema(description = "补充说明", example = "有 3 年陪诊经验，熟悉省医院各科室")
    @Size(max = 200, message = "补充说明不能超过 200 字")
    private String remark;
}
