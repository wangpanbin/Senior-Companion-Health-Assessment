package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.entity.OrderStatusLog;

import java.time.LocalDateTime;

/**
 * 订单状态流转时间线节点。
 *
 * <p>对应 {@code GET /api/order/{id}/timeline} 的响应（{@code docs/api/03-order.md} §10）。</p>
 *
 * <h3>{@code operatorName} 是快照，不是实时查询</h3>
 *
 * <p>取自 {@code order_status_log.operator_name}，写入时就把操作人的姓名定格下来。
 * 实时 JOIN {@code sys_user} 去取姓氏有两个问题：一是「老婆改过昵称之后，
 * 三个月前那笔订单的操作人变成了新昵称」，历史记录就不成其为历史；
 * 二是操作人账号被删（或陪诊员资质被清）之后，时间线上会出现空白节点。</p>
 *
 * <p>{@code fromStatus} 刻意不下发：前端渲染时间线只需要「这一步是什么状态、
 * 谁在什么时候做的、备注是什么」，上一步是什么状态是由上一条记录表达出来的。
 * 多下发一个字段就等于多一个前端可能渲染错的地方。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
@Data
@Accessors(chain = true)
@Schema(description = "订单状态流转时间线节点")
public class OrderTimelineVO {

    @Schema(description = "该节点对应的状态枚举名", example = "ACCEPTED")
    private String status;

    @Schema(description = "状态中文", example = "已接单")
    private String statusLabel;

    @Schema(description = "操作人姓名快照（系统自动流转时字段消失）", example = "李建军")
    private String operatorName;

    @Schema(description = "操作人角色：ELDER / FAMILY / COMPANION / ADMIN / SYSTEM", example = "COMPANION")
    private String operatorRole;

    @Schema(description = "备注", example = "已接单")
    private String remark;

    @Schema(description = "发生时间", example = "2026-09-15 17:02:11")
    private LocalDateTime operateTime;

    public static OrderTimelineVO of(OrderStatusLog row) {
        if (row == null) {
            return null;
        }
        return new OrderTimelineVO()
                .setStatus(row.getToStatus())
                .setStatusLabel(OrderStatus.labelOf(row.getToStatus()))
                .setOperatorName(row.getOperatorName())
                .setOperatorRole(row.getOperatorRole())
                .setRemark(row.getRemark())
                .setOperateTime(row.getOperateTime());
    }
}
