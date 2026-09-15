package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.Complaint;

/**
 * 我的投诉列表查询入参（{@code docs/api/06-review-complaint.md} §6）。
 *
 * <p>{@code role} 只是<b>范围筛选</b>而不是范围本身：
 * 不传时返回「我投诉的 + 投诉我的」的并集，传了则收窄一半。
 * 两种情况的可见范围都不会超出「与我相关」，
 * 因此这个参数无论怎么传都不构成越权 —— 这正是它敢做成可选参数的前提。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "我的投诉列表查询入参")
public class ComplaintQuery extends PageQuery<Complaint> {

    @Schema(description = "处理状态筛选：PENDING / PROCESSING / RESOLVED / REJECTED", example = "PENDING")
    private String status;

    @Schema(description = "视角：AS_COMPLAINANT 我投诉的 / AS_TARGET 投诉我的；不传为两者并集",
            example = "AS_COMPLAINANT")
    private String role;

    /** MyBatis-Plus 分页对象 */
    public Page<Complaint> toMpPage() {
        return toPage(new Page<>());
    }
}
