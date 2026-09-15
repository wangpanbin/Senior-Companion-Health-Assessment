package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.CompanionAuditRecord;

/**
 * 资质申请列表查询入参（{@code docs/api/08-admin.md} §1）。
 *
 * <p>{@code keyword} 同时匹配姓名与手机号：管理员手上往往只有其中一个
 * （用户来电时通常只说「我叫李四」或只报手机号），做成两个参数会逼管理员
 * 每次先想「我这次该填哪个框」。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "资质申请列表查询入参")
public class AdminAuditQuery extends PageQuery<CompanionAuditRecord> {

    @Schema(description = "审核状态：PENDING / APPROVED / REJECTED，不传为全部", example = "PENDING")
    private String auditStatus;

    @Schema(description = "姓名 / 手机号模糊搜索", example = "李四")
    private String keyword;

    @Schema(description = "提交时间起（yyyy-MM-dd）", example = "2026-09-01")
    private String startDate;

    @Schema(description = "提交时间止（yyyy-MM-dd，含当天）", example = "2026-09-30")
    private String endDate;

    /** MyBatis-Plus 分页对象 */
    public Page<CompanionAuditRecord> toMpPage() {
        return toPage(new Page<>());
    }
}
