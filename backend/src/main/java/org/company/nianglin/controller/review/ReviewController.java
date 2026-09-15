package org.company.nianglin.controller.review;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.Result;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ReviewCreateDTO;
import org.company.nianglin.dto.ReviewQuery;
import org.company.nianglin.service.ReviewService;
import org.company.nianglin.vo.CompanionScoreVO;
import org.company.nianglin.vo.ReviewCreateResultVO;
import org.company.nianglin.vo.ReviewVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评价接口。
 *
 * <p>对应 {@code docs/api/06-review-complaint.md} §1 ~ §4。</p>
 *
 * <h3>写接口只放行 FAMILY</h3>
 *
 * <p>评价必须由下单家属发出：陪诊员给自己刷分、老人代评价都会破坏评分的可信度。
 * 因此这里用注解收口，而不是在 Service 里做角色判断 ——
 * 「谁能调」是接口的属性，写在 Controller 上比散在业务逻辑里容易审计。</p>
 *
 * <h3>读接口放行全部已登录角色</h3>
 *
 * <p>陪诊员的评价列表与评分是<b>公开口碑数据</b>（老人家属选人时要看），
 * 因此不限定角色；而「查某订单的评价」隐含订单信息，归属由
 * {@code OrderService.requireInvolved} 在 Service 层判定，否则 3004。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@Slf4j
@RestController
@RequestMapping("/api/review")
@RequiredArgsConstructor
@Tag(name = "06-评价", description = "订单评价与陪诊员评分聚合")
public class ReviewController {

    private final ReviewService reviewService;

    @Operation(summary = "提交评价",
            description = "仅下单家属可提交，且订单状态必须为「已完成」（否则 6001）。"
                    + "一单一评由数据库唯一索引保证，重复提交返回 6002。"
                    + "提交成功后订单自动推进为「已评价」")
    @PreAuthorize("hasRole('" + RoleConstants.FAMILY + "')")
    @PostMapping
    public Result<ReviewCreateResultVO> create(@Valid @RequestBody ReviewCreateDTO dto) {
        return Result.success("感谢您的评价", reviewService.create(dto));
    }

    @Operation(summary = "查询订单评价",
            description = "须为该订单相关方（否则 3004）。未评价时 data 为 null —— "
                    + "「订单不存在」「无权查看」与「还没评价」是三种不同结果，不能混为一谈")
    @GetMapping("/order/{orderId}")
    public Result<ReviewVO> byOrder(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("orderId") Long orderId) {
        return Result.success(reviewService.byOrder(orderId));
    }

    @Operation(summary = "陪诊员评价列表",
            description = "已登录即可查看；被管理员判定为无效的评价不出现在此列表中")
    @GetMapping("/companion/{companionId}")
    public Result<PageResult<ReviewVO>> byCompanion(
            @Parameter(description = "陪诊员用户 ID", example = "10088") @PathVariable("companionId") Long companionId,
            @Valid ReviewQuery query) {
        return Result.success(reviewService.byCompanion(companionId, query));
    }

    @Operation(summary = "陪诊员评分聚合",
            description = "平均分与 SELECT ROUND(AVG(score), 2) 口径一致；"
                    + "无有效评价时返回 0.00 与 0 条，而不是 null")
    @GetMapping("/companion/{companionId}/score")
    public Result<CompanionScoreVO> score(
            @Parameter(description = "陪诊员用户 ID", example = "10088") @PathVariable("companionId") Long companionId) {
        return Result.success(reviewService.score(companionId));
    }
}
