package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.entity.OrderReview;
import org.company.nianglin.util.MaskUtil;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单评价（对外唯一出口）。
 *
 * <p>对应 {@code docs/api/06-review-complaint.md} §2（订单评价）/ §3（陪诊员评价列表）。</p>
 *
 * <h3>匿名：不是「隐藏姓名」，而是「换一个名字」</h3>
 *
 * <p>匿名评价的 {@code familyName} 返回固定串 {@code 匿***}，
 * 而不是返回 {@code null} 或空串。理由是前端要渲染「匿*** 评价了您」这一行；
 * 返回 null 会让前端不得不为匿名场景写一套分支排版，
 * 而任何一次排版改动都要在两个分支上各改一遍。</p>
 *
 * <h3>⚠️ 非匿名评价的姓名也做脱敏（与文档示例的一处刻意偏离）</h3>
 *
 * <p>文档 §2 的示例里非匿名评价显示的是家属全名（{@code "张三"}）。
 * 实现改为脱敏（{@code "张*"}），原因是：
 * <b>评列表是「已登录即可读」的</b>（见 §3 的权限列），
 * 任何陪诊员都能翻另一个陪诊员的评价列表 ——
 * 此时把家属真实姓名逐个展示出去，与 AGENT.md 的「隐私最小化」冲突，
 * 而脱敏不损失任何业务能力：评价的价值在分数与文字，不在家属叫什么。</p>
 *
 * <p>匿名场景仍然被完整覆盖（返回 {@code 匿***}），M7 的验收项不受影响。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@Data
@Accessors(chain = true)
@Schema(description = "订单评价")
public class ReviewVO {

    /** 匿名评价对外展示的固定姓名 */
    public static final String ANONYMOUS_NAME = "匿***";

    @Schema(description = "评价 ID", example = "2001")
    private Long id;

    @Schema(description = "订单 ID", example = "1001")
    private Long orderId;

    @Schema(description = "订单号", example = "NL20260915000001")
    private String orderNo;

    @Schema(description = "评分 1–5 星", example = "5")
    private Integer score;

    @Schema(description = "评价标签", example = "[\"准时\",\"耐心\",\"沟通清楚\"]")
    private List<String> tags;

    @Schema(description = "评价文字", example = "小李很耐心，全程陪着老人，取药排队也帮忙。")
    private String content;

    @Schema(description = "是否匿名", example = "false")
    private Boolean isAnonymous;

    @Schema(description = "评价人姓名（匿名时为 匿***，非匿名也做脱敏）", example = "张*")
    private String familyName;

    @Schema(description = "被评价陪诊员用户 ID", example = "10088")
    private Long companionId;

    @Schema(description = "陪诊员姓名（脱敏）", example = "李*")
    private String companionName;

    @Schema(description = "陪诊员回复", example = "感谢信任，祝老人早日康复。")
    private String companionReply;

    @Schema(description = "评价时间", example = "2026-09-20 18:30:00")
    private LocalDateTime createTime;

    /**
     * 装配。
     *
     * @param entity        评价实体
     * @param familyName    评价人真实姓名（已解密）；匿名时本方法会忽略它
     * @param companionName 陪诊员姓名
     * @param tags          已解析的标签列表；传 {@code null} 按空列表返回
     */
    public static ReviewVO of(OrderReview entity, String familyName, String companionName, List<String> tags) {
        if (entity == null) {
            return null;
        }
        boolean anonymous = entity.getIsAnonymous() != null && entity.getIsAnonymous() == 1;
        return new ReviewVO()
                .setId(entity.getId())
                .setOrderId(entity.getOrderId())
                .setOrderNo(entity.getOrderNo())
                .setScore(entity.getScore())
                .setTags(tags == null ? List.of() : tags)
                .setContent(entity.getContent())
                .setIsAnonymous(anonymous)
                // 匿名分支必须在脱敏之前短路：先脱敏再判断匿名，
                // 将来任何一次顺序调整都可能让真实姓名漏出去
                .setFamilyName(anonymous ? ANONYMOUS_NAME : MaskUtil.name(familyName))
                .setCompanionId(entity.getCompanionId())
                .setCompanionName(MaskUtil.name(companionName))
                .setCompanionReply(entity.getCompanionReply())
                .setCreateTime(entity.getCreateTime());
    }
}
