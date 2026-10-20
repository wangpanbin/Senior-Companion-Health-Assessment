package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 订单候选推荐结果（V3.4 · FR-Service）。
 *
 * <h3>推荐 ≠ 派单</h3>
 *
 * <p>本响应只是「系统认为谁更合适」的排序结果，
 * <b>接单权始终在陪诊员手里</b>（roadmap 决策 4.4）。
 * 家属看到的是候选，不是「已分配的陪诊员」。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
@Data
@Accessors(chain = true)
@Schema(description = "订单候选推荐结果")
public class OrderRecommendVO {

    @Schema(description = "被匹配的订单 ID", example = "1001")
    private Long orderId;

    @Schema(description = "通过过滤链的候选总数（不只是返回的 Top N）", example = "12")
    private Integer total;

    @Schema(description = "Top N 候选，按加权总分降序")
    private List<CompanionMatchVO> candidates = new ArrayList<>();
}
