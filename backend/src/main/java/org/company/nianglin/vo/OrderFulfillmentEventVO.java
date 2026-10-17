package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.FulfillmentEventType;
import org.company.nianglin.constant.FulfillmentRisk;

import java.time.LocalDateTime;

/**
 * 履约事件条目（V3.1 · T4）。
 *
 * <p>对应 {@code order_fulfillment_event} 表的一行。<b>只增不改</b>，
 * 因此这里呈现的就是历史事实，不存在「后来被改过」的可能。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@Data
@Accessors(chain = true)
@Schema(description = "履约事件条目")
public class OrderFulfillmentEventVO {

    @Schema(description = "事件类型", example = "FULFILLMENT_URGENT")
    private String eventType;

    @Schema(description = "事件类型中文", example = "履约风险升高")
    private String eventLabel;

    @Schema(description = "变更前风险等级；首次事件为 null")
    private String fromRisk;

    @Schema(description = "变更前风险中文", example = "等待时间较长")
    private String fromRiskLabel;

    @Schema(description = "变更后风险等级", example = "URGENT")
    private String toRisk;

    @Schema(description = "变更后风险中文", example = "履约风险较高")
    private String toRiskLabel;

    @Schema(description = "触发来源：USER-用户操作 / ADMIN-管理员 / SYSTEM-系统自动")
    private String triggerSource;

    @Schema(description = "操作人角色快照：FAMILY / COMPANION / ADMIN / SYSTEM")
    private String operatorRole;

    @Schema(description = "备注（写入前已过合规词表）")
    private String remark;

    @Schema(description = "发生时间")
    private LocalDateTime operateTime;

    public static OrderFulfillmentEventVO of(String eventType, String fromRisk, String toRisk,
                                             String triggerSource, String operatorRole,
                                             String remark, LocalDateTime operateTime) {
        OrderFulfillmentEventVO vo = new OrderFulfillmentEventVO();
        vo.setEventType(eventType);
        vo.setEventLabel(FulfillmentEventType.labelOf(eventType));
        vo.setFromRisk(fromRisk);
        vo.setFromRiskLabel(labelOfRisk(fromRisk));
        vo.setToRisk(toRisk);
        vo.setToRiskLabel(labelOfRisk(toRisk));
        vo.setTriggerSource(triggerSource);
        vo.setOperatorRole(operatorRole);
        vo.setRemark(remark);
        vo.setOperateTime(operateTime);
        return vo;
    }

    /** 非法风险值原样返回（脏数据不该让整个详情页 500） */
    private static String labelOfRisk(String risk) {
        return risk == null ? null : FulfillmentRisk.labelOf(risk);
    }
}
