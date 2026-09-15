package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;
import java.time.LocalDateTime;

/**
 * OrderReview —— 对应表 {@code order_review}。
 *
 * <p>订单评价表</p>
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
@TableName("order_review")
public class OrderReview extends BaseEntity {

    /**
     * 订单 ID（唯一，一单一评）
     */
    @Schema(description = "订单 ID（唯一，一单一评）")
    private Long orderId;

    /**
     * 订单号快照
     */
    @Schema(description = "订单号快照")
    private String orderNo;

    /**
     * 评价人（下单家属）用户 ID
     */
    @Schema(description = "评价人（下单家属）用户 ID")
    private Long familyId;

    /**
     * 被服务老人档案 ID
     */
    @Schema(description = "被服务老人档案 ID")
    private Long elderId;

    /**
     * 被评价陪诊员用户 ID
     */
    @Schema(description = "被评价陪诊员用户 ID")
    private Long companionId;

    /**
     * 评分 1-5 星
     */
    @Schema(description = "评分 1-5 星")
    private Integer score;

    /**
     * 评价标签 JSON 数组，如 ["准时","耐心"]，最多 5 个
     */
    @Schema(description = "评价标签 JSON 数组，如 ['准时','耐心']，最多 5 个")
    private String tags;

    /**
     * 评价文字，5-500 字
     */
    @Schema(description = "评价文字，5-500 字")
    private String content;

    /**
     * 是否匿名：0-否 1-是（匿名时列表与详情均不暴露家属姓名）
     */
    @TableField("is_anonymous")
    @Schema(description = "是否匿名：0-否 1-是（匿名时列表与详情均不暴露家属姓名）")
    private Integer isAnonymous;

    /**
     * 陪诊员回复
     */
    @Schema(description = "陪诊员回复")
    private String companionReply;

    /**
     * 回复时间
     */
    @Schema(description = "回复时间")
    private LocalDateTime replyTime;

    /**
     * 是否有效评价：0-管理员判定无效（不计入评分聚合）1-有效
     */
    @TableField("is_valid")
    @Schema(description = "是否有效评价：0-管理员判定无效（不计入评分聚合）1-有效")
    private Integer isValid;

}
