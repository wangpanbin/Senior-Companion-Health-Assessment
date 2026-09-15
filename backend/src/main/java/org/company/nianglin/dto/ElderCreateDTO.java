package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.ValidationPatterns;

import java.time.LocalDate;

/**
 * 新增老人档案入参。
 *
 * <p>对应 {@code POST /api/user/elder}（{@code docs/api/02-elder-family.md} §7）。</p>
 *
 * <p><b>不接收 {@code age}</b>：年龄由 {@code birth_date} 实时算出来。
 * 如果落库一个年龄字段，第二年它就会静静变成错的 —— 这类「缓慢过期」的冗余字段
 * 在老年人业务里尤其危险（影响陪诊员匹配与适老文案）。</p>
 *
 * <p><b>不接收 {@code userId}</b>：档案归属哪个人只能由服务端从令牌推导，
 * 从请求体收 {@code userId} 等于让前端决定「这条数据算谁的」。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Data
@Accessors(chain = true)
@Schema(description = "新增老人档案入参")
public class ElderCreateDTO {

    @Schema(description = "姓名，2-20 字符", example = "张德海",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "姓名不能为空")
    @Size(min = 2, max = 20, message = "姓名长度需在 2-20 个字符之间")
    private String name;

    @Schema(description = "性别：MALE-男 / FEMALE-女", example = "MALE",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "性别不能为空")
    @Pattern(regexp = ValidationPatterns.GENDER, message = "性别只能是 MALE 或 FEMALE")
    private String gender;

    @Schema(description = "出生日期，yyyy-MM-dd（年龄由后端计算）", example = "1948-03-12",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "出生日期不能为空")
    @Past(message = "出生日期必须早于今天")
    private LocalDate birthDate;

    @Schema(description = "身份证号，18 位；服务端 AES 加密存储，不回显", example = "460101194803120011")
    @Pattern(regexp = ValidationPatterns.ID_CARD_OPTIONAL, message = "身份证号格式不正确")
    private String idCard;

    @Schema(description = "老人联系电话", example = "13911112222")
    @Pattern(regexp = ValidationPatterns.PHONE_OPTIONAL, message = "手机号格式不正确")
    private String phone;

    @Schema(description = "常用地址（文字地址，一期不做地图导航）",
            example = "海南省海口市美兰区人民大道12号3栋501")
    @Size(max = 200, message = "地址不能超过 200 个字符")
    private String address;

    @Schema(description = "紧急联系人姓名", example = "张四")
    @Size(max = 20, message = "紧急联系人姓名不能超过 20 个字符")
    private String emergencyContact;

    @Schema(description = "紧急联系人电话", example = "13899998888")
    @Pattern(regexp = ValidationPatterns.PHONE_OPTIONAL, message = "紧急联系人手机号格式不正确")
    private String emergencyPhone;

    /**
     * 病史备注。
     *
     * <p>⚠️ 合规红线：这个字段<b>只做记录与展示</b>，系统不得据此给出任何判断或建议。
     * 它存在的意义是让陪诊员知道「老人平时吃什么药、对什么过敏」，而不是让系统当医生。</p>
     */
    @Schema(description = "病史备注（仅记录，系统不做诊断、不给用药建议）",
            example = "高血压、2型糖尿病，长期服药")
    @Size(max = 500, message = "病史备注不能超过 500 个字符")
    private String medicalHistory;

    @Schema(description = "过敏史备注（仅记录）", example = "青霉素过敏")
    @Size(max = 500, message = "过敏史备注不能超过 500 个字符")
    private String allergyHistory;

    @Schema(description = "行动能力：SELF-可自理 / ASSIST-需搀扶 / WHEELCHAIR-需轮椅（用于匹配陪诊员）",
            example = "ASSIST")
    @Pattern(regexp = ValidationPatterns.MOBILITY_LEVEL,
            message = "行动能力只能是 SELF / ASSIST / WHEELCHAIR")
    private String mobilityLevel;

    @Schema(description = "常去医院", example = "海南省人民医院")
    @Size(max = 100, message = "常去医院不能超过 100 个字符")
    private String favoriteHospital;

    @Schema(description = "备注", example = "听力较差，沟通需大声")
    @Size(max = 200, message = "备注不能超过 200 个字符")
    private String remark;
}
