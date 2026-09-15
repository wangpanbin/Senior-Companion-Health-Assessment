package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.AuditStatus;
import org.company.nianglin.entity.CompanionAuditRecord;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 资质申请（管理端，对外唯一出口）。
 *
 * <p>对应 {@code docs/api/08-admin.md} §一 {@code AuditApplicationVO} / §1 / §2。</p>
 *
 * <h3>身份证号一律只出脱敏串</h3>
 *
 * <p>文档 §2 实现要点给了一个二选一：单独开「查看完整身份证号」的敏感接口 + 记日志，
 * 或者本接口只返回脱敏串、让管理员对着证件照片人工核对。这里选<b>后者</b>，
 * 理由是前者需要额外一套「敏感字段访问审计」，而它一旦漏记日志，
 * 泄露的就是无法追溯的；而对着一张身份证照片核对姓名与号码，
 * 本来就是人工审核这一步在做的事，并没有因为少一个字段而变难。</p>
 *
 * <h3>证件材料是数组而不是拼接串</h3>
 *
 * <p>数据库里 {@code certificates} 是 JSON 列（形如
 * {@code [{"name":"身份证正面","url":"/uploads/..."}]}）。
 * 直接把它当字符串下发会让前端拿 {@code JSON.parse} 再解一次，
 * 而解析失败的兜底逻辑就会出现在每一个用它的页面上。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@Accessors(chain = true)
@Schema(description = "资质申请")
public class AuditApplicationVO {

    @Schema(description = "申请 ID", example = "501")
    private Long id;

    @Schema(description = "申请人用户 ID", example = "10099")
    private Long userId;

    @Schema(description = "用户名", example = "lisi")
    private String username;

    @Schema(description = "真实姓名", example = "李四")
    private String realName;

    @Schema(description = "手机号（脱敏）", example = "139****6677")
    private String phone;

    @Schema(description = "身份证号（脱敏）", example = "4601**********1234")
    private String idCard;

    @Schema(description = "服务区域", example = "海口市美兰区")
    private String serviceArea;

    @Schema(description = "可服务时段", example = "周一至周五 08:00–18:00")
    private String availableTime;

    @Schema(description = "证件材料")
    private List<Certificate> certificates;

    @Schema(description = "审核状态：PENDING / APPROVED / REJECTED", example = "PENDING")
    private String auditStatus;

    @Schema(description = "审核状态中文", example = "待审核")
    private String auditStatusLabel;

    @Schema(description = "驳回原因（仅驳回时返回）", example = "身份证照片不清晰，请重新上传")
    private String rejectReason;

    @Schema(description = "管理员内部备注")
    private String auditRemark;

    @Schema(description = "申请补充说明")
    private String applyRemark;

    @Schema(description = "提交时间", example = "2026-09-10 09:00:00")
    private LocalDateTime submitTime;

    @Schema(description = "审核时间", example = "2026-09-11 10:00:00")
    private LocalDateTime auditTime;

    @Schema(description = "审核人姓名（脱敏）；未审核时不返回", example = "王*")
    private String auditorName;

    /** 单份证件材料 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "证件材料项")
    public static class Certificate {

        @Schema(description = "材料名称", example = "身份证正面")
        private String name;

        @Schema(description = "文件 URL", example = "/uploads/202609/id1.jpg")
        private String url;
    }

    /**
     * 装配。
     *
     * <p><b>铁律（与 M3 的 {@code ElderVO.ofDetail} 一致）：本方法只接收已脱敏的值</b>，
     * 不接收身份证 / 手机号明文。脱敏在 Service 里「解密后就地完成」，
     * VO 既不持密钥也不持明文，因此 {@code toString()} 与日志都带不走它们。</p>
     *
     * @param entity           资质申请记录
     * @param username         申请人用户名
     * @param maskedPhone      已脱敏手机号
     * @param maskedIdCard     已脱敏身份证号
     * @param certificates     已解析的证件列表；传 {@code null} 按空列表返回
     * @param maskedAuditor    已脱敏的审核人姓名；未审核传 {@code null}
     * @param includeAuditNote 是否返回驳回原因与内部备注（列表页返回，避免一屏之内把驳回理由
     *                         铺满；详情页才需要完整信息）
     */
    public static AuditApplicationVO of(CompanionAuditRecord entity, String username, String maskedPhone,
                                       String maskedIdCard, List<Certificate> certificates,
                                       String maskedAuditor, boolean includeAuditNote) {
        if (entity == null) {
            return null;
        }
        return new AuditApplicationVO()
                .setId(entity.getId())
                .setUserId(entity.getApplicantUserId())
                .setUsername(username)
                .setRealName(entity.getRealName())
                .setPhone(maskedPhone)
                .setIdCard(maskedIdCard)
                .setServiceArea(entity.getServiceArea())
                .setAvailableTime(entity.getAvailableTime())
                .setCertificates(certificates == null ? List.of() : certificates)
                .setAuditStatus(entity.getAuditStatus())
                .setAuditStatusLabel(AuditStatus.labelOf(entity.getAuditStatus()))
                .setRejectReason(includeAuditNote ? entity.getRejectReason() : null)
                .setAuditRemark(includeAuditNote ? entity.getAuditRemark() : null)
                .setApplyRemark(includeAuditNote ? entity.getApplyRemark() : null)
                .setSubmitTime(entity.getSubmitTime())
                .setAuditTime(entity.getAuditTime())
                .setAuditorName(maskedAuditor);
    }
}
