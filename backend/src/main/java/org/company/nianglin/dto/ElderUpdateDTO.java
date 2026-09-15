package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.ValidationPatterns;

import java.time.LocalDate;

/**
 * 修改老人档案入参（局部更新）。
 *
 * <p>对应 {@code PUT /api/user/elder/{id}}（{@code docs/api/02-elder-family.md} §9）。</p>
 *
 * <p><b>「不修改」与「清空」怎么区分</b> —— 这是个必须一次说清、否则后患无穷的问题。
 * HTTP 的 {@code PATCH} 语义在 JSON 里表达不了「字段没传」和「字段传了 null」的差别
 * （Jackson 把两者都解析成 {@code null}）。本项目的取舍是：</p>
 *
 * <ul>
 *   <li><b>{@code null}（未传）= 不修改</b>，保留数据库原值；</li>
 *   <li><b>空串 {@code ""}（对 String 字段）= 清空</b>，把该列置为 {@code null}；</li>
 *   <li>非 String 字段（{@code gender} / {@code birthDate} / {@code mobilityLevel}）没有「空串」形态，
 *       一律只能改不能清 —— 这几个字段清空也没有业务含义。</li>
 * </ul>
 *
 * <p>这么做的好处是前端表单可以「原样回传整个对象」，用户没动的字段不会被误清空。
 * 代价是明确无法通过本接口把 String 字段设回 {@code null} 以外的「空值」形态 —— 可接受。</p>
 *
 * <p>⚠️ 这里字段是<b>故意与 {@link ElderCreateDTO} 重复声明</b>的，没有抽公共父类：
 * 父类上的 {@code @NotBlank} 无法在子类里被「取消」，抽父类会导致修改接口被迫要求必填。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Data
@Accessors(chain = true)
@Schema(description = "修改老人档案入参（局部更新，null 表示不修改，空串表示清空）")
public class ElderUpdateDTO {

    @Schema(description = "姓名，2-20 字符；不传表示不修改", example = "张德海")
    @Size(min = 2, max = 20, message = "姓名长度需在 2-20 个字符之间")
    private String name;

    @Schema(description = "性别：MALE-男 / FEMALE-女；不传表示不修改", example = "MALE")
    @Pattern(regexp = ValidationPatterns.GENDER, message = "性别只能是 MALE 或 FEMALE")
    private String gender;

    @Schema(description = "出生日期，yyyy-MM-dd；不传表示不修改", example = "1948-03-12")
    @Past(message = "出生日期必须早于今天")
    private LocalDate birthDate;

    @Schema(description = "身份证号，18 位；不传表示不修改", example = "460101194803120011")
    @Pattern(regexp = ValidationPatterns.ID_CARD_OPTIONAL, message = "身份证号格式不正确")
    private String idCard;

    @Schema(description = "老人联系电话；不传表示不修改", example = "13911112222")
    @Pattern(regexp = ValidationPatterns.PHONE_OPTIONAL, message = "手机号格式不正确")
    private String phone;

    @Schema(description = "常用地址；不传表示不修改")
    @Size(max = 200, message = "地址不能超过 200 个字符")
    private String address;

    @Schema(description = "紧急联系人姓名；不传表示不修改", example = "张四")
    @Size(max = 20, message = "紧急联系人姓名不能超过 20 个字符")
    private String emergencyContact;

    @Schema(description = "紧急联系人电话；不传表示不修改", example = "13899998888")
    @Pattern(regexp = ValidationPatterns.PHONE_OPTIONAL, message = "紧急联系人手机号格式不正确")
    private String emergencyPhone;

    @Schema(description = "病史备注（仅记录）；不传表示不修改")
    @Size(max = 500, message = "病史备注不能超过 500 个字符")
    private String medicalHistory;

    @Schema(description = "过敏史备注（仅记录）；不传表示不修改")
    @Size(max = 500, message = "过敏史备注不能超过 500 个字符")
    private String allergyHistory;

    @Schema(description = "行动能力：SELF / ASSIST / WHEELCHAIR；不传表示不修改", example = "ASSIST")
    @Pattern(regexp = ValidationPatterns.MOBILITY_LEVEL,
            message = "行动能力只能是 SELF / ASSIST / WHEELCHAIR")
    private String mobilityLevel;

    @Schema(description = "常去医院；不传表示不修改", example = "海南省人民医院")
    @Size(max = 100, message = "常去医院不能超过 100 个字符")
    private String favoriteHospital;

    @Schema(description = "备注；不传表示不修改")
    @Size(max = 200, message = "备注不能超过 200 个字符")
    private String remark;
}
