package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.ValidationPatterns;

/**
 * 资质证件材料项。
 *
 * <p>序列化后以 JSON 字符串落在 {@code companion_audit_record.certificates} 列
 * （形如 {@code [{"name":"健康证","url":"/uploads/xxx.jpg"}]}）。
 * 之所以不做成独立表：证件只是审核时的附件清单，没有独立查询与统计需求，
 * 拆表会让「提交申请」变成多表写入，收益不抵复杂度。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Data
@Accessors(chain = true)
@Schema(description = "资质证件材料项")
public class CertificateItem {

    @Schema(description = "证件名称", example = "健康证", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "证件名称不能为空")
    @Size(max = 50, message = "证件名称过长")
    private String name;

    @Schema(description = "证件访问地址（来自文件上传接口）",
            example = "/uploads/202609/health-cert.jpg", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "证件地址不能为空")
    @Size(max = 255, message = "证件地址过长")
    @Pattern(regexp = ValidationPatterns.FILE_URL, message = "证件地址格式不正确")
    private String url;
}
