package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.time.LocalDate;

/**
 * ElderProfile —— 对应表 {@code elder_profile}。
 *
 * <p>老人档案表</p>
 *
 * <p>主键与审计字段（id / createTime / updateTime / deleted）继承自 {@link BaseEntity}。</p>
 *
 * <p>⚠️ 本类为持久化实体，禁止直接作为接口返回值；对外统一转 VO，
 * 避免密码、身份证号、完整手机号等敏感字段外泄。</p>
 *
 * @since M1
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("elder_profile")
public class ElderProfile extends BaseEntity {

    /**
     * 关联的老人登录账号 ID；由家属代建的档案可为空
     */
    @Schema(description = "关联的老人登录账号 ID；由家属代建的档案可为空")
    private Long userId;

    /**
     * 姓名
     */
    @Schema(description = "姓名")
    private String name;

    /**
     * 性别：MALE-男 / FEMALE-女
     */
    @Schema(description = "性别：MALE-男 / FEMALE-女")
    private String gender;

    /**
     * 出生日期（年龄由后端计算，不落库，避免年度失效）
     */
    @Schema(description = "出生日期（年龄由后端计算，不落库，避免年度失效）")
    private LocalDate birthDate;

    /**
     * 身份证号（**AES 密文**），接口返回脱敏串，日志禁止打印
     */
    @Schema(description = "身份证号（**AES 密文**），接口返回脱敏串，日志禁止打印")
    private String idCard;

    /**
     * 联系电话，返回脱敏
     */
    @Schema(description = "联系电话，返回脱敏")
    private String phone;

    /**
     * 常用地址（文字地址，一期不做地图导航），返回门牌号打码
     */
    @Schema(description = "常用地址（文字地址，一期不做地图导航），返回门牌号打码")
    private String address;

    /**
     * 紧急联系人姓名
     */
    @Schema(description = "紧急联系人姓名")
    private String emergencyContact;

    /**
     * 紧急联系人电话，返回脱敏
     */
    @Schema(description = "紧急联系人电话，返回脱敏")
    private String emergencyPhone;

    /**
     * 病史备注（**仅记录，系统不做诊断、不给用药建议**）
     */
    @Schema(description = "病史备注（**仅记录，系统不做诊断、不给用药建议**）")
    private String medicalHistory;

    /**
     * 过敏史备注（仅记录）
     */
    @Schema(description = "过敏史备注（仅记录）")
    private String allergyHistory;

    /**
     * 行动能力：SELF-自理 / ASSIST-需搀扶 / WHEELCHAIR-轮椅，用于匹配陪诊员
     */
    @Schema(description = "行动能力：SELF-自理 / ASSIST-需搀扶 / WHEELCHAIR-轮椅，用于匹配陪诊员")
    private String mobilityLevel;

    /**
     * 常去医院
     */
    @Schema(description = "常去医院")
    private String favoriteHospital;

    /**
     * 绑定状态：BOUND-已绑定家属 / UNBOUND-未绑定
     */
    @Schema(description = "绑定状态：BOUND-已绑定家属 / UNBOUND-未绑定")
    private String bindStatus;

    /**
     * 建档人（家属）用户 ID
     */
    @Schema(description = "建档人（家属）用户 ID")
    private Long createBy;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

}
