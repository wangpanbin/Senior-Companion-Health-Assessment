package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单履约详情（V3.1 · T4）。
 *
 * <p>回答两个问题：<b>这笔单现在等得怎么样了</b>、<b>它是怎么变成这样的</b>。
 * 后者靠 {@link #events} —— 没有事件流水，管理员看到「等了很久」也无从判断
 * 是没人接、还是接了又被拒、还是平台已经协调过。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@Data
@Accessors(chain = true)
@Schema(description = "订单履约详情（风险等级 + 事件流水）")
public class OrderFulfillmentVO {

    @Schema(description = "订单 ID")
    private Long orderId;

    @Schema(description = "订单号", example = "NL20261017000001")
    private String orderNo;

    @Schema(description = "订单状态", example = "PENDING")
    private String status;

    @Schema(description = "订单状态中文", example = "待接单")
    private String statusLabel;

    @Schema(description = "履约风险等级", example = "URGENT")
    private String fulfillmentRisk;

    @Schema(description = "履约风险中文", example = "履约风险较高")
    private String fulfillmentRiskLabel;

    @Schema(description = "从下单到当前的等待分钟数；非待接单时为 0", example = "45")
    private Integer waitMinutes;

    @Schema(description = "累计催单次数", example = "1")
    private Integer urgeCount;

    @Schema(description = "前端据此决定是否展示「催促接单」按钮；非安全边界", example = "true")
    private Boolean canUrge;

    @Schema(description = "下次最早可催时间；已达次数上限或当前不可催时为 null")
    private LocalDateTime nextUrgeAvailableAt;

    @Schema(description = "履约事件流水，**按发生时间升序**（从下单到当前）")
    private List<OrderFulfillmentEventVO> events;

    public static OrderFulfillmentVO of(CompanionOrderSnapshot order, List<OrderFulfillmentEventVO> events) {
        OrderFulfillmentVO vo = new OrderFulfillmentVO();
        vo.setOrderId(order.orderId());
        vo.setOrderNo(order.orderNo());
        vo.setStatus(order.status());
        vo.setStatusLabel(order.statusLabel());
        vo.setFulfillmentRisk(order.risk());
        vo.setFulfillmentRiskLabel(order.riskLabel());
        vo.setWaitMinutes(order.waitMinutes());
        vo.setUrgeCount(order.urgeCount());
        vo.setCanUrge(order.canUrge());
        vo.setNextUrgeAvailableAt(order.nextUrgeAvailableAt());
        // 空事件流返回空列表而不是 null：前端直接 .length 即可，不必判空
        vo.setEvents(events == null ? List.of() : events);
        return vo;
    }

    /**
     * 订单侧的履约快照。
     *
     * <p>做成 record 而不是直接传实体，是为了让 VO 工厂不依赖 Entity，
     * 与本仓库「Entity 不得直接作为接口返回」的约定一致。</p>
     */
    public record CompanionOrderSnapshot(Long orderId, String orderNo, String status, String statusLabel,
                                         String risk, String riskLabel, Integer waitMinutes,
                                         Integer urgeCount, Boolean canUrge,
                                         LocalDateTime nextUrgeAvailableAt) {
    }
}
