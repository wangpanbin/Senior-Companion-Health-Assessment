package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.entity.OrderReview;
import org.company.nianglin.util.MaskUtil;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评价巡查列表项（管理端）。
 *
 * <p>对应 {@code docs/api/08-admin.md} §29「评价巡查列表」。</p>
 *
 * <h3>为什么不直接复用 {@link ReviewVO}</h3>
 *
 * <p>{@code ReviewVO} 是「评价人视角」的出口：它的 {@code familyName} 在匿名时返回
 * {@code 匿***}、非匿名时返回脱敏串，且它的定位是<b>公开口碑</b> ——
 * 任何陪诊员都能读另一个陪诊员的评价列表。</p>
 *
 * <p>评价巡查的读者是<b>管理员</b>，他要看的恰恰是公开列表里<b>看不到</b>的那些：
 * 已被裁定无效的评价（{@code isValid=false}）。</p>
 *
 * <p>所以这里独立建一个 VO，而不是给 {@code ReviewVO} 加字段 ——
 * 给公开出口加「管理员专属视角」的字段，迟早会有人忘了判断调用方是谁就把它返回出去。</p>
 *
 * <h3>姓名一律脱敏</h3>
 *
 * <p>与 {@code ReviewVO} 同口径：评价的价值在分数与文字，不在���属叫什么。
 * 巡查页是列表页，不是详情页，不适用订单详情「全名豁免」。</p>
 *
 * @author 银龄伴诊团队
 * @since E4
 */
@Data
@Accessors(chain = true)
@Schema(description = "评价巡查列表项（管理端）")
public class AdminReviewVO {

    @Schema(description = "评价 ID", example = "30001")
    private Long id;

    @Schema(description = "关联订单 ID", example = "1031")
    private Long orderId;

    @Schema(description = "订单号", example = "NL20260820000031")
    private String orderNo;

    @Schema(description = "评分（1–5 星）", example = "5")
    private Integer score;

    @Schema(description = "标签列表", example = "[\"准时\",\"耐心\"]")
    private List<String> tags;

    @Schema(description = "评价正文", example = "小李很耐心，全程陪着老人，取药排队也帮忙。")
    private String content;

    @Schema(description = "是否匿名评价", example = "false")
    private Boolean anonymous;

    @Schema(description = "评价家属姓名（脱敏；匿名时为固定串）", example = "张*")
    private String familyName;

    @Schema(description = "陪诊员用户 ID", example = "307")
    private Long companionId;

    @Schema(description = "陪诊员姓名（脱敏）", example = "李*")
    private String companionName;

    @Schema(description = "陪诊员回复正文（未回复时为 null）", example = "感谢认可，后续会继续保持。")
    private String companionReply;

    @Schema(description = "陪诊员回复时间（未回复时为 null）", example = "2026-09-14 20:00:00")
    private LocalDateTime replyTime;

    /**
     * 有效性：{@code true} = 有效 / {@code false} = 已被管理员裁定无效。
     *
     * <p>巡查页的核心字段：{@code isValid=false} 的评价在公开列表里看不到，
     * 只有这里能翻出来。</p>
     *
     * <p>字段名与 {@link ReviewVO#isValid} <b>刻意保持一致</b> ——
     * 两个入口（申诉联动 / 巡查页）把评价对象喂给同一个裁定对话框组件，
     * 字段名一旦分叉，同一个组件就得写两套取值判断。</p>
     */
    @Schema(description = "是否有效：false = 已被管理员裁定无效", example = "true")
    private Boolean isValid;

    @Schema(description = "评价时间", example = "2026-09-14 20:00:00")
    private LocalDateTime createTime;

    /**
     * 装配。
     *
     * @param entity        评价实体
     * @param familyName    评价人真实姓名（已解密）
     * @param companionName 陪诊员姓名
     * @param tags          已解析的标签列表
     */
    public static AdminReviewVO of(OrderReview entity, String familyName, String companionName,
                                   List<String> tags) {
        if (entity == null) {
            return null;
        }
        boolean anonymous = entity.getIsAnonymous() != null && entity.getIsAnonymous() == 1;
        // is_valid: 1 → true / 0 → false；null 视为有效（兜底，V1 老数据无 is_valid 字段）
        boolean valid = entity.getIsValid() == null || entity.getIsValid() == 1;
        return new AdminReviewVO()
                .setId(entity.getId())
                .setOrderId(entity.getOrderId())
                .setOrderNo(entity.getOrderNo())
                .setScore(entity.getScore())
                .setTags(tags == null ? List.of() : tags)
                .setContent(entity.getContent())
                .setAnonymous(anonymous)
                // 匿名分支必须在脱敏之前短路：先脱敏再判断匿名，
                // 将来任何一次顺序调整都可能让真实姓名漏出去
                .setFamilyName(anonymous ? ReviewVO.ANONYMOUS_NAME : MaskUtil.name(familyName))
                .setCompanionId(entity.getCompanionId())
                .setCompanionName(MaskUtil.name(companionName))
                .setCompanionReply(entity.getCompanionReply())
                .setReplyTime(entity.getReplyTime())
                .setIsValid(valid)
                .setCreateTime(entity.getCreateTime());
    }
}
