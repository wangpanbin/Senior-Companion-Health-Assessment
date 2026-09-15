package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * CompanionProfile —— 对应表 {@code companion_profile}。
 *
 * <p>陪诊员业务资料表</p>
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
@TableName("companion_profile")
public class CompanionProfile extends BaseEntity {

    /**
     * 关联用户 ID（唯一，一个账号一份资料）
     */
    @Schema(description = "关联用户 ID（唯一，一个账号一份资料）")
    private Long userId;

    /**
     * 真实姓名，返回脱敏
     */
    @Schema(description = "真实姓名，返回脱敏")
    private String realName;

    /**
     * 身份证号（**AES 密文**）
     */
    @Schema(description = "身份证号（**AES 密文**）")
    private String idCard;

    /**
     * 性别：MALE / FEMALE
     */
    @Schema(description = "性别：MALE / FEMALE")
    private String gender;

    /**
     * 出生日期
     */
    @Schema(description = "出生日期")
    private LocalDate birthDate;

    /**
     * 联系电话，返回脱敏
     */
    @Schema(description = "联系电话，返回脱敏")
    private String phone;

    /**
     * 服务区域
     */
    @Schema(description = "服务区域")
    private String serviceArea;

    /**
     * 可服务时段
     */
    @Schema(description = "可服务时段")
    private String availableTime;

    /**
     * 个人简介 / 服务说明
     */
    @Schema(description = "个人简介 / 服务说明")
    private String introduction;

    /**
     * 资质证书编号
     */
    @Schema(description = "资质证书编号")
    private String certificateNo;

    /**
     * 健康证有效期，过期自动置为不可接单
     */
    @Schema(description = "健康证有效期，过期自动置为不可接单")
    private LocalDate healthCertExpire;

    /**
     * 资质状态：PENDING-待审核 / APPROVED-已通过 / REJECTED-已驳回
     */
    @Schema(description = "资质状态：PENDING-待审核 / APPROVED-已通过 / REJECTED-已驳回")
    private String auditStatus;

    /**
     * 最近一次驳回原因
     */
    @Schema(description = "最近一次驳回原因")
    private String rejectReason;

    /**
     * 审核管理员用户 ID
     */
    @Schema(description = "审核管理员用户 ID")
    private Long auditAdminId;

    /**
     * 审核时间
     */
    @Schema(description = "审核时间")
    private LocalDateTime auditTime;

    /**
     * 接单状态：AVAILABLE-可接单 / REST-休息中
     */
    @Schema(description = "接单状态：AVAILABLE-可接单 / REST-休息中")
    private String workStatus;

    /**
     * 平均评分（0.00-5.00），由评价聚合后冗余更新，避免实时聚合
     */
    @Schema(description = "平均评分（0.00-5.00），由评价聚合后冗余更新，避免实时聚合")
    private BigDecimal score;

    /**
     * 有效评价数
     */
    @Schema(description = "有效评价数")
    private Integer reviewCount;

    /**
     * 累计已完成订单数（仅 COMPLETED/REVIEWED 计入）
     */
    @Schema(description = "累计已完成订单数（仅 COMPLETED/REVIEWED 计入）")
    private Integer orderCount;

    /**
     * 累计接单数（含进行中）
     */
    @Schema(description = "累计接单数（含进行中）")
    private Integer acceptCount;

    /**
     * 备注
     */
    @Schema(description = "备注")
    private String remark;

}
