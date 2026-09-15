package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求。
 *
 * <p>对应文档：{@code docs/api/01-auth-user.md} §2 注册。</p>
 *
 * <p>⚠️ {@code role} 用正则<b>只允许</b> ELDER / FAMILY / COMPANION 三种。
 * 管理员账号只能由数据库初始化脚本或管理后台创建，注册接口永远不可能产出 ADMIN ——
 * 这是权限体系的根，一旦漏掉就是提权漏洞。</p>
 *
 * @author 银龄伴诊团队
 */
@Data
@Schema(description = "注册请求")
public class RegisterDTO {

    @Schema(description = "用户名，4-20 位字母数字下划线", example = "zhangsan", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入用户名")
    @Pattern(regexp = "^[A-Za-z0-9_]{4,20}$", message = "用户名为 4-20 位字母、数字或下划线")
    private String username;

    @Schema(description = "手机号，11 位", example = "13800000000", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入手机号")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "请输入正确的 11 位手机号")
    private String phone;

    @Schema(description = "密码，6-32 位且需含字母与数字", example = "abc123456", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入密码")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d)\\S{6,32}$", message = "密码为 6-32 位，且需同时包含字母与数字")
    private String password;

    @Schema(description = "昵称，建议用「张大爷」这类称呼", example = "张三", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入昵称")
    @Size(min = 2, max = 20, message = "昵称长度为 2-20 个字符")
    private String nickname;

    @Schema(description = "注册角色，不允许 ADMIN", example = "FAMILY", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请选择注册角色")
    @Pattern(regexp = "^(ELDER|FAMILY|COMPANION)$", message = "注册角色只能为 ELDER / FAMILY / COMPANION")
    private String role;

    @Schema(description = "验证码标识", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请先获取验证码")
    private String captchaKey;

    @Schema(description = "验证码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "请输入验证码")
    @Size(min = 4, max = 6, message = "验证码为 4-6 位")
    private String captchaCode;
}
