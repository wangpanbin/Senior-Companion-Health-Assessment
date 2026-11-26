package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;

import java.time.LocalDateTime;

/**
 * ProductFeedback —— 对应表 {@code product_feedback}。
 *
 * <p>产品反馈（V6）：与订单<b>完全解耦</b>的用户建议通道。</p>
 *
 * <p>为什么单独一张表而不是给 {@code complaint} 加类型：{@code complaint.order_id} /
 * {@code target_user_id} 均为 {@code NOT NULL}，语义是「A 就某笔订单投诉 B」；
 * 产品反馈没有订单、没有被投诉人。改成可空会连带影响投诉侧所有
 * {@code requireInvolved(orderId)} 的归属校验分支与管理端「关联订单」列，
 * 且「投诉」一词会同时代表仲裁与产品建议两件事，审计语义也会混。</p>
 *
 * <p>主键与审计字段（id / createTime / updateTime / deleted）继承自 {@link BaseEntity}。</p>
 *
 * <p>⚠️ 本类为持久化实体，禁止直接作为接口返回值；对外统一转
 * {@link org.company.nianglin.vo.FeedbackVO}，避免 {@code handleAdminId} 之类的内部字段外泄。</p>
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("product_feedback")
public class ProductFeedback extends BaseEntity {

    /** 提交人用户 ID（取自登录态，不接受前端传入） */
    @Schema(description = "提交人用户 ID")
    private Long submitterId;

    /** 提交人角色：FAMILY / COMPANION */
    @Schema(description = "提交人角色：FAMILY / COMPANION")
    private String submitterRole;

    /** 分类：FEATURE-功能建议 / EXPERIENCE-使用体验 / DEFECT-缺陷问题 / OTHER-其他 */
    @Schema(description = "分类：FEATURE-功能建议 / EXPERIENCE-使用体验 / DEFECT-缺陷问题 / OTHER-其他")
    private String type;

    /** 反馈内容，10-1000 字 */
    @Schema(description = "反馈内容，10-1000 字")
    private String content;

    /**
     * 截图 URL 数组，最多 3 张（JSON 列）。
     *
     * <p>与 {@code complaint.evidence} 同款存法：实体里是 JSON 字符串，
     * 由 Service 层用 {@code ObjectMapper} 解析成 {@code List<String>} 后交给 VO。</p>
     */
    @Schema(description = "截图 URL 数组（JSON 字符串），最多 3 张")
    private String images;

    /** 处理状态：PENDING / PROCESSING / RESOLVED / REJECTED（只可正向流转） */
    @Schema(description = "处理状态：PENDING / PROCESSING / RESOLVED / REJECTED（只可正向流转）")
    private String status;

    /** 处理管理员用户 ID */
    @Schema(description = "处理管理员用户 ID")
    private Long handleAdminId;

    /** 处理说明，10-500 字；仅终态对提交人可见 */
    @Schema(description = "处理说明，10-500 字；仅终态对提交人可见")
    private String handleResult;

    /** 处理时间 */
    @Schema(description = "处理时间")
    private LocalDateTime handleTime;
}
