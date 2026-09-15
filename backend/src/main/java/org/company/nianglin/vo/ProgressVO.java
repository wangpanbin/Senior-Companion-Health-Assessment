package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.CheckinNode;
import org.company.nianglin.constant.OrderStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 陪诊进度快照（{@code docs/api/04-companion-execution.md} §4）。
 *
 * <p><b>为什么要有这个接口</b>：家属端进页面时如果只等 WebSocket 推送，
 * 页面会先空白一段时间；如果有人压根没连上，就一直空白下去。
 * 所以约定是「先拉一次快照铺底，之后靠推送增量更新」——
 * 快照负责「现在到哪了」，推送负责「又到哪了」。</p>
 *
 * <p>{@code nextNode} 只是「按顺序排下来的下一个节点」，<b>不是承诺</b>：
 * 流程允许跳过取药环节，所以它可能永远不会被打上。
 * 它存在的意义是让前端能提示「下一步：取药」，而不是做校验。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@Data
@Accessors(chain = true)
@Schema(description = "陪诊进度快照")
public class ProgressVO {

    @Schema(description = "订单 ID", example = "1001")
    private Long orderId;

    @Schema(description = "订单状态枚举名", example = "IN_SERVICE")
    private String orderStatus;

    @Schema(description = "订单状态中文", example = "服务中")
    private String orderStatusLabel;

    @Schema(description = "当前节点枚举名；尚无打卡时为 null", example = "IN_CONSULT")
    private String currentNode;

    @Schema(description = "当前节点中文", example = "就诊中")
    private String currentNodeLabel;

    @Schema(description = "下一节点枚举名；已到末节点时为 null", example = "TAKE_MEDICINE")
    private String nextNode;

    @Schema(description = "下一节点中文", example = "取药")
    private String nextNodeLabel;

    @Schema(description = "进度百分比（当前节点序号 / 6 × 100）", example = "50")
    private Integer progressPercent;

    @Schema(description = "已完成节点枚举名列表（按节点顺序升序）", example = "[\"DEPART\",\"ARRIVE\",\"IN_CONSULT\"]")
    private List<String> finishedNodes;

    @Schema(description = "最后一次打卡时间；尚无打卡时为订单创建时间", example = "2026-09-20 09:45:00")
    private LocalDateTime lastUpdateTime;

    /* ------------------------------------------------------------------ */
    /* 工厂方法                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * @param lastNode  已完成节点中的最后一个（按顺序值最大），无打卡传 {@code null}
     * @param finished  已完成节点名列表（升序）
     * @param initial   订单创建时间，用作「尚无打卡」时的 {@code lastUpdateTime}
     */
    public static ProgressVO of(Long orderId, String orderStatus, CheckinNode lastNode,
                                List<String> finished, LocalDateTime initial) {
        CheckinNode next = lastNode == null ? CheckinNode.DEPART : lastNode.next();
        return new ProgressVO()
                .setOrderId(orderId)
                .setOrderStatus(orderStatus)
                .setOrderStatusLabel(OrderStatus.labelOf(orderStatus))
                .setCurrentNode(lastNode == null ? null : lastNode.name())
                .setCurrentNodeLabel(lastNode == null ? null : lastNode.getLabel())
                .setNextNode(next == null ? null : next.name())
                .setNextNodeLabel(next == null ? null : next.getLabel())
                .setProgressPercent(lastNode == null ? 0 : lastNode.getSort() * 100 / CheckinNode.total())
                .setFinishedNodes(finished == null ? List.of() : finished)
                .setLastUpdateTime(initial);
    }
}
