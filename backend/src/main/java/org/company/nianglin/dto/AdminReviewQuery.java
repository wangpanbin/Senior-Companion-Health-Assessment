package org.company.nianglin.dto;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.PageQuery;
import org.company.nianglin.entity.OrderReview;

/**
 * 评价巡查列表查询入参（管理端）。
 *
 * <p>对应 {@code docs/api/08-admin.md} §29「评价巡查列表」。</p>
 *
 * <h3>为什么单独一个 DTO，而不是复用 {@code ReviewQuery}</h3>
 *
 * <p>{@code ReviewQuery} 是「陪诊员评价列表」的入参（{@code docs/api/06-review-complaint.md} §3），
 * 那条链路的语义是「<b>公开口碑</b>」—— 已登录即可读，且<b>硬编码只返回有效评价</b>
 * （{@code ReviewServiceImpl#byCompanion} 里写死了 {@code eq(is_valid, 1)}）。
 * 评价巡查要的恰恰相反：管理员要能看到<b>已被裁定无效</b>的那些，
 * 否则「裁完就消失」的评价等于从来没被巡查过。</p>
 *
 * <p>两者的可见性边界也不同：{@code ReviewQuery} 那条链路的评价人是<b>公众</b>，
 * 评价巡查的读者是<b>管理员</b>。混在一个 DTO 里，
 * 「加一个能看无效评价的参数」就会顺手把公众那条链路也放开了 ——
 * 所以这里单独建一个，不复用。</p>
 *
 * @author 银龄伴诊团队
 * @since E4
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "评价巡查列表查询入参（管理端）")
public class AdminReviewQuery extends PageQuery<OrderReview> {

    @Schema(description = "陪诊员用户 ID（只看某位陪诊员收到的评价）", example = "307")
    private Long companionId;

    @Schema(description = "只看评分 ≥ N 星（1–5）", example = "4")
    @Min(value = 1, message = "评分筛选不能低于 1 星")
    @Max(value = 5, message = "评分筛选不能高于 5 星")
    private Integer minScore;

    @Schema(description = "只看评分 ≤ N 星（1–5）", example = "2")
    @Min(value = 1, message = "评分筛选不能低于 1 星")
    @Max(value = 5, message = "评分筛选不能高于 5 星")
    private Integer maxScore;

    /**
     * 有效性筛选：{@code null} = 不限 / {@code true} = 只看有效 / {@code false} = 只看已被裁定无效。
     *
     * <p>用 {@code Boolean} 而不是 {@code String}：三态语义（不限 / 有效 / 无效）
     * 用字符串表达需要额外的哨兵值（空串 / "all"），而那个哨兵值迟早会漏判。</p>
     */
    @Schema(description = "有效性筛选：不传 = 全部；true = 只看有效；false = 只看已被裁定无效",
            example = "false")
    private Boolean isValid;

    @Schema(description = "评价内容 / 订单号模糊搜索", example = "NL20260915")
    private String keyword;

    @Schema(description = "评价时间起（yyyy-MM-dd）", example = "2026-09-01")
    private String startDate;

    @Schema(description = "评价时间止（yyyy-MM-dd，含当天）", example = "2026-09-30")
    private String endDate;

    /** MyBatis-Plus 分页对象 */
    public Page<OrderReview> toMpPage() {
        return toPage(new Page<>());
    }
}
