package org.company.nianglin.controller.order;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.Result;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.OrderCancelDTO;
import org.company.nianglin.dto.OrderCompleteDTO;
import org.company.nianglin.dto.OrderCreateDTO;
import org.company.nianglin.dto.OrderHallQuery;
import org.company.nianglin.dto.OrderQuery;
import org.company.nianglin.dto.OrderUrgeDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.dto.OrderRejectDTO;
import org.company.nianglin.dto.FeeItemCreateDTO;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.MatchingService;
import org.company.nianglin.service.OrderFeeItemService;
import org.company.nianglin.service.FulfillmentService;
import org.company.nianglin.service.OrderService;
import org.company.nianglin.vo.OrderAcceptResultVO;
import org.company.nianglin.vo.OrderCreateResultVO;
import org.company.nianglin.vo.OrderFeeItemSummaryVO;
import org.company.nianglin.vo.OrderFeeItemVO;
import org.company.nianglin.vo.OrderFlowResultVO;
import org.company.nianglin.vo.OrderTimelineVO;
import org.company.nianglin.vo.OrderFulfillmentVO;
import org.company.nianglin.vo.OrderRecommendVO;
import org.company.nianglin.vo.OrderUrgeResultVO;
import org.company.nianglin.vo.OrderVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * 陪诊订单接口。
 *
 * <p>对应 {@code docs/api/03-order.md} §1 ~ §10。</p>
 *
 * <h3>注解只写在"有些角色完全不该进来"的接口上</h3>
 *
 * <p>例如创建订单只能是家属（{@code hasRole('FAMILY')}）、大厅只能是陪诊员。
 * 而详情、时间线、我的列表这类接口<b>放行全部已登录角色</b>，
 * 由 Service 判断「这一单跟不跟你有关」—— 因为「相关方」这个概念横跨四个角色：
 * 下单家属、接单陪诊员、就诊老人、处理纠纷的管理员，
 * 用注解表达需要写四个 {@code hasAnyRole} 且一个都表达不了归属。</p>
 *
 * <h3>老人账号（ELDER）为什么不需要额外处理</h3>
 *
 * <p>除 GET 外的所有写接口对 ELDER 一律 403，由 {@code ElderReadOnlyInterceptor}
 * 全局兜住，不需要每个接口各判断一次。所以这里不写 {@code @AllowElderWrite} ——
 * 老人在订单模块里<b>本来就没有任何写操作</b>，全部由家属代操作。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
@Slf4j
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
@Tag(name = "03-陪诊订单", description = "下单、接单、状态流转与时间线")
public class OrderController {

    private final OrderService orderService;
    /** 履约保障（V3.1）——催单 */
    private final FulfillmentService fulfillmentService;
    private final MatchingService matchingService;
    private final OrderFeeItemService orderFeeItemService;

    /* ==================== 查询 ==================== */

    @Operation(summary = "我的订单列表",
            description = "按角色自动区分数据范围：家属看自己下的单、陪诊员看自己接的单、"
                    + "老人看自己作为就诊人的订单、管理员看全部。列表页姓名脱敏且不返回地址")
    @GetMapping
    public Result<PageResult<OrderVO>> myOrders(@Valid OrderQuery query) {
        return Result.success(orderService.myOrders(query));
    }

    @Operation(summary = "待接单订单大厅",
            description = "仅 COMPANION 角色且资质已通过审核（否则 2003）。"
                    + "只返回未过期的待接单订单，并排除自己已拒过的单。与「我的订单」不同，"
                    + "大厅会返回医院地址 —— 陪诊员需要先知道去哪儿")
    @PreAuthorize("hasRole('" + RoleConstants.COMPANION + "')")
    @GetMapping("/hall")
    public Result<PageResult<OrderVO>> hall(@Valid OrderHallQuery query) {
        return Result.success(orderService.hall(query));
    }

    @Operation(summary = "订单详情",
            description = "须为该订单的相关方（下单家属 / 接单陪诊员 / 就诊老人本人 / 管理员），"
                    + "否则 3004。此处姓名返回全名")
    @GetMapping("/{id}")
    public Result<OrderVO> detail(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("id") Long id) {
        return Result.success(orderService.detail(id));
    }

    @Operation(summary = "状态流转时间线",
            description = "按发生时间升序返回 order_status_log。操作人姓名为写入时的快照，"
                    + "不会因为日后改名而改变历史")
    @GetMapping("/{id}/timeline")
    public Result<List<OrderTimelineVO>> timeline(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("id") Long id) {
        return Result.success(orderService.timeline(id));
    }

    /* ==================== 家属操作 ==================== */

    @Operation(summary = "创建订单",
            description = "家属为绑定的老人下单。就诊时间必须晚于当前时间（否则 3005），"
                    + "老人必须在自己名下（否则 2006）。服务费不传则由后端按规则计算")
    @PreAuthorize("hasRole('" + RoleConstants.FAMILY + "')")
    @PostMapping
    public Result<OrderCreateResultVO> create(@Valid @RequestBody OrderCreateDTO dto) {
        return Result.success("下单成功，正在为您匹配陪诊员", orderService.create(dto));
    }

    @Operation(summary = "订单履约详情（V3.1）",
            description = "返回订单当前的履约风险等级、等待时长、催单次数与完整事件流水"
                    + "（按发生时间升序，从下单到当前）。"
                    + "可见性与订单详情完全一致：下单家属 / 接单陪诊员 / 就诊老人 / 管理员，其余 3004。"
                    + "事件流为空时返回空数组，不返回 null")
    @GetMapping("/{id}/fulfillment")
    public Result<OrderFulfillmentVO> fulfillmentDetail(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("id") Long id) {
        return Result.success(orderService.fulfillmentDetail(id));
    }

    @Operation(summary = "候选陪诊员推荐（V3.4 · 智能调度）",
            description = "为该订单推荐 Top N 候选陪诊员，按五维加权匹配分排序："
                    + "区域 30% / 时间 25% / 距离 20% / 服务能力 15% / 评分 10%（权重可配置）。"
                    + "每条候选都带<b>可解释性</b>：命中理由（reasons）与降级说明（notes），"
                    + "例如「未填写坐标，距离维度记 0」——<b>不会给出一个没有理由的分数</b>。"
                    + "仅下单家属可查（他人 3004，非家属角色 403）。"
                    + "<b>推荐 ≠ 派单</b>：本接口不产生任何订单状态变更，接单权始终在陪诊员手里")
    @PreAuthorize("hasRole('" + RoleConstants.FAMILY + "')")
    @GetMapping("/{id}/recommend-companions")
    public Result<OrderRecommendVO> recommendCompanions(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("id") Long id,
            @Parameter(description = "返回条数，默认 10，上限 20", example = "10")
            @RequestParam(value = "limit", required = false) Integer limit) {
        CompanionOrder order = orderService.requireInvolved(id);
        // 归属校验：即使角色对了，也必须是「这一单的下单家属」
        if (!Objects.equals(order.getFamilyId(), SecurityUtils.currentUserId())) {
            throw new BusinessException(ResultCode.ORDER_NO_PERMISSION,
                    "只有下单家属可以查看该订单的候选陪诊员");
        }
        return Result.success(matchingService.recommendCompanions(order, normalizeLimit(limit)));
    }

    /** 推荐条数归一化：默认 10，上限 20（非法值一律落到默认值，不报错） */
    private int normalizeLimit(Integer limit) {
        if (limit == null || limit < 1) {
            return 10;
        }
        return Math.min(limit, 20);
    }

    @Operation(summary = "取消订单",
            description = "仅下单家属可取消，且仅「待接单」状态可取消（否则 3006）。"
                    + "已接单的订单需取消必须走管理员纠纷处理，避免陪诊员白跑")
    @PreAuthorize("hasRole('" + RoleConstants.FAMILY + "')")
    @PutMapping("/{id}/cancel")
    public Result<Void> cancel(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("id") Long id,
            @Valid @RequestBody OrderCancelDTO dto) {
        orderService.cancel(id, dto);
        return Result.success("订单已取消", null);
    }

    @Operation(summary = "催促接单（V3.1）",
            description = "仅下单家属、仅「待接单」、且履约风险已达「较高」时可催（否则 3008）。"
                    + "受冷却期与次数上限约束，过于频繁返回 3009。"
                    + "催单只会提醒**当前可接单**的陪诊员，不会打断休息中或离线的陪诊员。"
                    + "本接口**不改变订单状态**")
    @PreAuthorize("hasRole('" + RoleConstants.FAMILY + "')")
    @PostMapping("/{id}/urge")
    public Result<OrderUrgeResultVO> urge(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("id") Long id,
            @Valid @RequestBody(required = false) OrderUrgeDTO dto) {
        return Result.success("已提醒陪诊员尽快接单", fulfillmentService.urge(id, dto));
    }

    /* ==================== 陪诊员操作 ==================== */

    @Operation(summary = "接单",
            description = "乐观锁防超卖：多人同时接同一单时只有一人成功，"
                    + "被抢先返回 3003。资质未通过审核返回 2003")
    @PreAuthorize("hasRole('" + RoleConstants.COMPANION + "')")
    @PostMapping("/{id}/accept")
    public Result<OrderAcceptResultVO> accept(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("id") Long id) {
        return Result.success("接单成功", orderService.accept(id));
    }

    @Operation(summary = "拒单",
            description = "不改变订单状态，仅记录一条拒单记录，使该订单不再出现在本陪诊员的大厅列表里")
    @PreAuthorize("hasRole('" + RoleConstants.COMPANION + "')")
    @PostMapping("/{id}/reject")
    public Result<Void> reject(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("id") Long id,
            @Valid @RequestBody OrderRejectDTO dto) {
        orderService.reject(id, dto);
        return Result.success("已拒单", null);
    }

    @Operation(summary = "开始服务",
            description = "须为本单陪诊员（否则 4003），且状态必须为「已接单」（否则 3002）。"
                    + "对「待接单」的订单调用本接口返回 3002 而不是 4003")
    @PreAuthorize("hasRole('" + RoleConstants.COMPANION + "')")
    @PostMapping("/{id}/start")
    public Result<OrderFlowResultVO> start(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("id") Long id) {
        return Result.success("已开始服务", orderService.start(id));
    }

    @Operation(summary = "完成服务",
            description = "须为本单陪诊员，且状态必须为「服务中」。服务小结含诊断或用药建议类表述时返回 3007。"
                    + "完成后订单不会自动变为「已评价」，需家属提交评价")
    @PreAuthorize("hasRole('" + RoleConstants.COMPANION + "')")
    @PostMapping("/{id}/complete")
    public Result<OrderFlowResultVO> complete(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("id") Long id,
            @Valid @RequestBody OrderCompleteDTO dto) {
        return Result.success("服务已完成", orderService.complete(id, dto));
    }

    @Operation(summary = "录入费用明细",
            description = "本单陪诊员为订单记录一条费用明细（代垫/服务费，ADR-0009）。"
                    + "订单须处于 IN_SERVICE 或 COMPLETED；完成态录入会同步重算 actual_fee")
    @PreAuthorize("hasRole('" + RoleConstants.COMPANION + "')")
    @PostMapping("/{id}/fee-items")
    public Result<OrderFeeItemVO> createFeeItem(
            @Parameter(description = "订单 ID", example = "1025") @PathVariable("id") Long id,
            @Valid @RequestBody FeeItemCreateDTO dto) {
        return Result.success("已记录", orderFeeItemService.create(id, dto));
    }

    @Operation(summary = "查询费用明细",
            description = "相关方（下单家属 / 就诊老人 / 本单陪诊员 / 管理员）可查。"
                    + "返回明细与代垫/服务费分组合计；无明细且已有申报金额时返回 fallbackNotice 兜底提示")
    @GetMapping("/{id}/fee-items")
    public Result<OrderFeeItemSummaryVO> feeItems(
            @Parameter(description = "订单 ID", example = "1025") @PathVariable("id") Long id) {
        return Result.success(orderFeeItemService.listByOrder(id));
    }
}
