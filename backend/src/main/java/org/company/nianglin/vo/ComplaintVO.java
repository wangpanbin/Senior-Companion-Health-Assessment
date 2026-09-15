package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.ComplaintStatus;
import org.company.nianglin.constant.ComplaintType;
import org.company.nianglin.entity.Complaint;
import org.company.nianglin.util.MaskUtil;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 投诉（对外唯一出口）。
 *
 * <p>对应 {@code docs/api/06-review-complaint.md} §6（我的投诉列表）/ §7（投诉详情）。</p>
 *
 * <h3>处理结果只在已结案时出现</h3>
 *
 * <p>{@code handleResult} 在 {@code PENDING} / {@code PROCESSING} 阶段不返回，
 * 而不是返回空串。前端据此渲染「暂无处理结果」，比渲染一个空白的详情块更清楚。
 * 全局 Jackson 的 {@code non_null} 策略会自动让为 {@code null} 的字段消失，
 * 因此这里只需要不在未结案时赋值。</p>
 *
 * <h3>双方姓名一律脱敏</h3>
 *
 * <p>投诉详情是「双方 + 管理员」可见的（见 §7 权限列），
 * 也就是说<b>投诉人与被投诉人会互相看到对方的信息</b>。
 * 姓名脱敏让「管理员知道是谁」和「双方不必知道对面全名」同时成立 ——
 * 后者的重要性在于，投诉往往伴随着情绪，把全名摊开只会让冲突升级到平台之外。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@Data
@Accessors(chain = true)
@Schema(description = "投诉记录")
public class ComplaintVO {

    @Schema(description = "投诉 ID", example = "3001")
    private Long id;

    @Schema(description = "关联订单 ID", example = "1001")
    private Long orderId;

    @Schema(description = "订单号", example = "NL20260915000001")
    private String orderNo;

    @Schema(description = "投诉人用户 ID", example = "201")
    private Long complainantId;

    @Schema(description = "投诉人姓名（脱敏）", example = "张*")
    private String complainantName;

    @Schema(description = "被投诉人用户 ID", example = "10088")
    private Long targetUserId;

    @Schema(description = "被投诉人姓名（脱敏）", example = "李*")
    private String targetUserName;

    @Schema(description = "投诉类型：LATE / ATTITUDE / INCOMPLETE / FEE_DISPUTE / PRIVACY / OTHER", example = "LATE")
    private String type;

    @Schema(description = "投诉类型中文名", example = "迟到 / 未按时到达")
    private String typeLabel;

    @Schema(description = "投诉内容", example = "陪诊员比约定时间晚了 40 分钟到达，导致老人错过取号。")
    private String content;

    @Schema(description = "证据材料 URL 列表", example = "[\"/uploads/202609/ghi789.jpg\"]")
    private List<String> evidence;

    @Schema(description = "处理状态：PENDING / PROCESSING / RESOLVED / REJECTED", example = "PROCESSING")
    private String status;

    @Schema(description = "处理状态中文名", example = "处理中")
    private String statusLabel;

    @Schema(description = "处理结果说明（仅已结案 / 已驳回时返回）", example = "已核实陪诊员迟到，扣除信用分 5 分。")
    private String handleResult;

    @Schema(description = "提交时间", example = "2026-09-20 19:00:00")
    private LocalDateTime createTime;

    @Schema(description = "处理时间", example = "2026-09-21 10:00:00")
    private LocalDateTime handleTime;

    /**
     * 装配。
     *
     * @param entity            投诉实体
     * @param complainantName   投诉人姓名（本方法内就地脱敏）
     * @param targetUserName    被投诉人姓名（本方法内就地脱敏）
     * @param evidence          已解析的证据 URL 列表；传 {@code null} 按空列表返回
     */
    public static ComplaintVO of(Complaint entity, String complainantName, String targetUserName,
                                 List<String> evidence) {
        if (entity == null) {
            return null;
        }
        ComplaintStatus status = ComplaintStatus.of(entity.getStatus());
        boolean closed = status != null && status.isTerminal();
        return new ComplaintVO()
                .setId(entity.getId())
                .setOrderId(entity.getOrderId())
                .setOrderNo(entity.getOrderNo())
                .setComplainantId(entity.getComplainantId())
                .setComplainantName(MaskUtil.name(complainantName))
                .setTargetUserId(entity.getTargetUserId())
                .setTargetUserName(MaskUtil.name(targetUserName))
                .setType(entity.getType())
                .setTypeLabel(ComplaintType.labelOf(entity.getType()))
                .setContent(entity.getContent())
                .setEvidence(evidence == null ? List.of() : evidence)
                .setStatus(entity.getStatus())
                .setStatusLabel(ComplaintStatus.labelOf(entity.getStatus()))
                // 未结案时不下发处理结论：草稿状态的处理意见一旦被前端渲染出去，
                // 用户会以为投诉已经有结果了
                .setHandleResult(closed ? entity.getHandleResult() : null)
                .setCreateTime(entity.getCreateTime())
                .setHandleTime(entity.getHandleTime());
    }
}
