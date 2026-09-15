package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.time.LocalDateTime;

/**
 * AdminOperLog —— 对应表 {@code admin_oper_log}。
 *
 * <p>管理员操作日志表（只增不改不删）</p>
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
@TableName("admin_oper_log")
public class AdminOperLog extends BaseEntity {

    /**
     * 操作人用户 ID（管理员）
     */
    @Schema(description = "操作人用户 ID（管理员）")
    private Long operatorId;

    /**
     * 操作人姓名快照
     */
    @Schema(description = "操作人姓名快照")
    private String operatorName;

    /**
     * 操作类型：AUDIT_COMPANION-审核资质 / DISABLE_USER-封禁 / ENABLE_USER-解封 / RESET_PASSWORD-重置密码 / ARBITRATE_ORDER-订单纠纷 / HANDLE_COMPLAINT-处理投诉 / PUBLISH_NOTICE-发布公告
     */
    @Schema(description = "操作类型：AUDIT_COMPANION-审核资质 / DISABLE_USER-封禁 / ENABLE_USER-解封 / RESET_PASSWORD-重置密码 / ARBITRATE_ORDER-订单纠纷 / HANDLE_COMPLAINT-处理投诉 / PUBLISH_NOTICE-发布公告")
    private String operType;

    /**
     * 目标类型：USER-用户 / ORDER-订单 / COMPANION-陪诊员 / COMPLAINT-投诉
     */
    @Schema(description = "目标类型：USER-用户 / ORDER-订单 / COMPANION-陪诊员 / COMPLAINT-投诉")
    private String targetType;

    /**
     * 目标主键 ID
     */
    @Schema(description = "目标主键 ID")
    private Long targetId;

    /**
     * 目标描述（如订单号 NL20260915000001）
     */
    @Schema(description = "目标描述（如订单号 NL20260915000001）")
    private String targetDesc;

    /**
     * 变更前状态
     */
    @Schema(description = "变更前状态")
    private String beforeStatus;

    /**
     * 变更后状态
     */
    @Schema(description = "变更后状态")
    private String afterStatus;

    /**
     * 操作备注 / 原因
     */
    @Schema(description = "操作备注 / 原因")
    private String remark;

    /**
     * 请求路径
     */
    @Schema(description = "请求路径")
    private String requestUrl;

    /**
     * 请求方法 GET/POST/PUT/DELETE
     */
    @Schema(description = "请求方法 GET/POST/PUT/DELETE")
    private String requestMethod;

    /**
     * 操作 IP
     */
    @Schema(description = "操作 IP")
    private String ip;

    /**
     * 操作时间
     */
    @Schema(description = "操作时间")
    private LocalDateTime operTime;

}
