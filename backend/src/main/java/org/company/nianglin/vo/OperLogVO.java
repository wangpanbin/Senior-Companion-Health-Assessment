package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.OperTargetType;
import org.company.nianglin.constant.OperType;
import org.company.nianglin.entity.AdminOperLog;
import org.company.nianglin.util.MaskUtil;

import java.time.LocalDateTime;

/**
 * 操作日志（{@code docs/api/08-admin.md} §一 {@code OperLogVO} / §12）。
 *
 * <h3>操作人姓名用快照</h3>
 *
 * <p>{@code admin_oper_log.operator_name} 是<b>写入时定格</b>的，
 * 这里直接读它而不是按 {@code operatorId} 实时查当前昵称。
 * 理由与订单的 {@code operatorName} 一致：日志是历史事实，
 * 管理员改名之后，历史的操作记录不该跟着改。</p>
 *
 * <h3>IP 不脱敏</h3>
 *
 * <p>IP 是审计的关键字段，脱敏之后就无法用于「是不是同一个人在操作」的判断。
 * 它与其他隐私字段的差别在于：这里的使用场景本身就是为了定位具体操作者。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@Accessors(chain = true)
@Schema(description = "管理员操作日志")
public class OperLogVO {

    @Schema(description = "日志 ID", example = "9001")
    private Long id;

    @Schema(description = "操作人 ID", example = "10001")
    private Long operatorId;

    @Schema(description = "操作人姓名（写入时快照，脱敏）", example = "王*")
    private String operatorName;

    @Schema(description = "操作类型：AUDIT_COMPANION / DISABLE_USER / ENABLE_USER / RESET_PASSWORD / "
            + "ARBITRATE_ORDER / HANDLE_COMPLAINT / PUBLISH_NOTICE", example = "ARBITRATE_ORDER")
    private String operType;

    @Schema(description = "操作类型中文名", example = "订单纠纷处理")
    private String operTypeLabel;

    @Schema(description = "目标类型：USER / ORDER / COMPANION / COMPLAINT", example = "ORDER")
    private String targetType;

    @Schema(description = "目标类型中文名", example = "订单")
    private String targetTypeLabel;

    @Schema(description = "目标 ID", example = "1001")
    private Long targetId;

    @Schema(description = "目标描述（如订单号）", example = "NL20260915000001")
    private String targetDesc;

    @Schema(description = "变更前状态", example = "IN_SERVICE")
    private String beforeStatus;

    @Schema(description = "变更后状态", example = "CANCELLED")
    private String afterStatus;

    @Schema(description = "操作备注 / 原因", example = "经核实陪诊员迟到 40 分钟且未提前告知")
    private String remark;

    @Schema(description = "请求路径", example = "/api/admin/order/1001/arbitrate")
    private String requestUrl;

    @Schema(description = "请求方法", example = "POST")
    private String requestMethod;

    @Schema(description = "操作 IP", example = "192.168.1.10")
    private String ip;

    @Schema(description = "操作时间", example = "2026-09-21 10:00:00")
    private LocalDateTime operTime;

    public static OperLogVO of(AdminOperLog log) {
        if (log == null) {
            return null;
        }
        OperTargetType targetType = OperTargetType.of(log.getTargetType());
        return new OperLogVO()
                .setId(log.getId())
                .setOperatorId(log.getOperatorId())
                .setOperatorName(MaskUtil.name(log.getOperatorName()))
                .setOperType(log.getOperType())
                .setOperTypeLabel(OperType.labelOf(log.getOperType()))
                .setTargetType(log.getTargetType())
                .setTargetTypeLabel(targetType == null ? log.getTargetType() : targetType.getLabel())
                .setTargetId(log.getTargetId())
                .setTargetDesc(log.getTargetDesc())
                .setBeforeStatus(log.getBeforeStatus())
                .setAfterStatus(log.getAfterStatus())
                .setRemark(log.getRemark())
                .setRequestUrl(log.getRequestUrl())
                .setRequestMethod(log.getRequestMethod())
                .setIp(log.getIp())
                .setOperTime(log.getOperTime());
    }
}
