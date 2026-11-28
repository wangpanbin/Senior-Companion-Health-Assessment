package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.FeedbackStatus;
import org.company.nianglin.constant.FeedbackType;
import org.company.nianglin.entity.ProductFeedback;
import org.company.nianglin.util.MaskUtil;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 产品反馈（对外唯一出口）。
 *
 * <p>对应 {@code docs/api/15-product-feedback.md} §一（数据模型）/ §2（我的反馈）/ §4（管理端列表）。</p>
 *
 * <h3>处理说明只在终态出现</h3>
 *
 * <p>{@code handleResult} 在 {@code PENDING} / {@code PROCESSING} 阶段<b>不返回</b>，
 * 而不是返回空串。管理员在「处理中」时写的那段是<b>工作草稿</b>，
 * 被前端渲染出去就等于告诉用户「已经有结论了」，而实际处理还在进行。
 * 全局 Jackson 的 {@code non_null} 策略自动让为 {@code null} 的字段消失，
 * 因此这里只需要不在未终态时赋值。</p>
 *
 * <h3>姓名一律脱敏</h3>
 *
 * <p>管理端列表会展示提交人姓名，客户端「我的反馈」里也带上同一个字段（本人看自己）。
 * 统一走 {@link MaskUtil#name} —— 客户端侧「本人看自己被打码」看着奇怪，
 * 但比「同一份 VO 到了管理端忘了打码」安全得多：一处分叉就是一次泄露机会。</p>
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
@Data
@Accessors(chain = true)
@Schema(description = "产品反馈记录")
public class FeedbackVO {

    @Schema(description = "反馈 ID", example = "5001")
    private Long id;

    @Schema(description = "提交人用户 ID", example = "201")
    private Long submitterId;

    @Schema(description = "提交人姓名（脱敏）", example = "张*")
    private String submitterName;

    @Schema(description = "提交人角色：FAMILY / COMPANION", example = "FAMILY")
    private String submitterRole;

    @Schema(description = "分类：FEATURE / EXPERIENCE / DEFECT / OTHER", example = "EXPERIENCE")
    private String type;

    @Schema(description = "分类中文名", example = "使用体验")
    private String typeLabel;

    @Schema(description = "反馈内容", example = "订单列表的字有点小，老人模式下也还是偏小。")
    private String content;

    @Schema(description = "截图 URL 列表（≤3 张）",
            example = "[\"/uploads/202611/9f8e7d6c5b4a39281706fedcba987654.jpg\"]")
    private List<String> images;

    @Schema(description = "处理状态：PENDING / PROCESSING / RESOLVED / REJECTED", example = "RESOLVED")
    private String status;

    @Schema(description = "处理状态中文名", example = "已采纳")
    private String statusLabel;

    @Schema(description = "处理说明（**仅终态返回**）",
            example = "已排入下一版：订单列表与用药页字号 +2px。")
    private String handleResult;

    @Schema(description = "提交时间", example = "2026-11-20 09:12:00")
    private LocalDateTime createTime;

    @Schema(description = "处理时间", example = "2026-11-20 10:30:00")
    private LocalDateTime handleTime;

    @Schema(description = "处理中是否超时（收口 A · P1-6 反馈链；由服务层聚合计算，非数据库字段）",
            example = "true")
    private Boolean overdue;

    @Schema(description = "已超出的整天数（未超时不返回）", example = "8")
    private Integer overdueDays;

    /**
     * 装配。
     *
     * @param entity        反馈实体
     * @param submitterName 提交人姓名（本方法内就地脱敏）
     * @param images        已解析的截图 URL 列表；传 {@code null} 按空列表返回
     */
    public static FeedbackVO of(ProductFeedback entity, String submitterName, List<String> images) {
        if (entity == null) {
            return null;
        }
        FeedbackStatus status = FeedbackStatus.of(entity.getStatus());
        boolean closed = status != null && status.isTerminal();
        return new FeedbackVO()
                .setId(entity.getId())
                .setSubmitterId(entity.getSubmitterId())
                .setSubmitterName(MaskUtil.name(submitterName))
                .setSubmitterRole(entity.getSubmitterRole())
                .setType(entity.getType())
                .setTypeLabel(FeedbackType.labelOf(entity.getType()))
                .setContent(entity.getContent())
                .setImages(images == null ? List.of() : images)
                .setStatus(entity.getStatus())
                .setStatusLabel(FeedbackStatus.labelOf(entity.getStatus()))
                // 未终态时不下发处理说明：中间态那段是工作草稿，渲染出去用户会以为已有结论
                .setHandleResult(closed ? entity.getHandleResult() : null)
                .setCreateTime(entity.getCreateTime())
                .setHandleTime(entity.getHandleTime());
    }
}
