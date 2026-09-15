package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.Complaint;

/**
 * 投诉列表查询入参（{@code docs/api/08-admin.md} §10）。
 *
 * <p>管理端与用户端的差别在于：这里<b>不加收窄条件</b>，看得到全部投诉。
 * 因此本 DTO 单独存在，而不是复用 {@code ComplaintQuery} ——
 * 复用会让「用户端必须限定 complainant/target」这条安全约束
 * 与「管理端可以全看」的矛盾藏在同一个类里，很容易在改动时改错方向。</p>
 *
 * @author 银龄伴诊团队
 * @since M9
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "投诉列表查询入参（管理端）")
public class AdminComplaintQuery extends PageQuery<Complaint> {

    @Schema(description = "处理状态：PENDING / PROCESSING / RESOLVED / REJECTED", example = "PENDING")
    private String status;

    @Schema(description = "投诉类型：LATE / ATTITUDE / INCOMPLETE / FEE_DISPUTE / PRIVACY / OTHER",
            example = "LATE")
    private String type;

    @Schema(description = "投诉内容 / 订单号模糊搜索", example = "NL20260915")
    private String keyword;

    @Schema(description = "提交时间起（yyyy-MM-dd）", example = "2026-09-01")
    private String startDate;

    @Schema(description = "提交时间止（yyyy-MM-dd，含当天）", example = "2026-09-30")
    private String endDate;

    /** MyBatis-Plus 分页对象 */
    public Page<Complaint> toMpPage() {
        return toPage(new Page<>());
    }
}
