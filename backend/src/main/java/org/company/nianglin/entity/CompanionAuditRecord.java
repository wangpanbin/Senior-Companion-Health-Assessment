package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.time.LocalDateTime;

/**
 * CompanionAuditRecord —— 对应表 {@code companion_audit_record}。
 *
 * <p>陪诊员资质申请记录表</p>
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
@TableName("companion_audit_record")
public class CompanionAuditRecord extends BaseEntity {

    /**
     * 申请人用户 ID
     */
    @Schema(description = "申请人用户 ID")
    private Long applicantUserId;

    /**
     * 真实姓名
     */
    @Schema(description = "真实姓名")
    private String realName;

    /**
     * 身份证号（**AES 密文**）
     */
    @Schema(description = "身份证号（**AES 密文**）")
    private String idCard;

    /**
     * 服务区域，如「海口市美兰区」
     */
    @Schema(description = "服务区域，如「海口市美兰区」")
    private String serviceArea;

    /**
     * 可服务时段，如「周一至周五 08:00-18:00」
     */
    @Schema(description = "可服务时段，如「周一至周五 08:00-18:00」")
    private String availableTime;

    /**
     * 证件材料 [{name,url}]，至少 1 项
     */
    @Schema(description = "证件材料 [{name,url}]，至少 1 项")
    private String certificates;

    /**
     * 申请补充说明
     */
    @Schema(description = "申请补充说明")
    private String applyRemark;

    /**
     * 审核状态：PENDING-待审核 / APPROVED-已通过 / REJECTED-已驳回
     */
    @Schema(description = "审核状态：PENDING-待审核 / APPROVED-已通过 / REJECTED-已驳回")
    private String auditStatus;

    /**
     * 驳回原因（驳回时必填，5-200 字）
     */
    @Schema(description = "驳回原因（驳回时必填，5-200 字）")
    private String rejectReason;

    /**
     * 审核管理员用户 ID
     */
    @Schema(description = "审核管理员用户 ID")
    private Long auditAdminId;

    /**
     * 管理员内部备注
     */
    @Schema(description = "管理员内部备注")
    private String auditRemark;

    /**
     * 提交时间
     */
    @Schema(description = "提交时间")
    private LocalDateTime submitTime;

    /**
     * 审核时间
     */
    @Schema(description = "审核时间")
    private LocalDateTime auditTime;

}
