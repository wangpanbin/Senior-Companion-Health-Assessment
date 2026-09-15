package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.CheckinNode;
import org.company.nianglin.entity.OrderCheckin;

import java.time.LocalDateTime;

/**
 * 打卡结果（{@code docs/api/04-companion-execution.md} §1 响应）。
 *
 * <p>只回前端「刚发生了什么」，不回整条订单 ——
 * 打卡页拿到结果后需要立即完成的只有三件事：提示成功、显示距离、刷新时间线。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@Data
@Accessors(chain = true)
@Schema(description = "打卡结果")
public class CheckinResultVO {

    @Schema(description = "打卡记录 ID", example = "8001")
    private Long checkinId;

    @Schema(description = "打卡节点枚举名", example = "ARRIVE")
    private String node;

    @Schema(description = "打卡节点中文", example = "到院")
    private String nodeLabel;

    @Schema(description = "与订单地址的直线距离（米）", example = "128")
    private Integer distance;

    @Schema(description = "是否异常打卡", example = "false")
    private Boolean isAbnormal;

    @Schema(description = "打卡时间", example = "2026-09-20 09:10:00")
    private LocalDateTime checkinTime;

    public static CheckinResultVO of(OrderCheckin row) {
        if (row == null) {
            return null;
        }
        return new CheckinResultVO()
                .setCheckinId(row.getId())
                .setNode(row.getNode())
                .setNodeLabel(CheckinNode.labelOf(row.getNode()))
                .setDistance(row.getDistance())
                .setIsAbnormal(row.getIsAbnormal() != null && row.getIsAbnormal() == 1)
                .setCheckinTime(row.getCheckinTime());
    }
}
