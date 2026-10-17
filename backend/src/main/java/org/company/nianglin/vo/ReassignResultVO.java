package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 管理员改派结果（V3.1 · T7，spec §2.7）。
 *
 * <p>带回<b>改派前后</b>的陪诊员，而不是只回一个成功标记：
 * 改派是「谁替谁」的操作，事后核对时要能一眼看出这一笔是从谁换到了谁。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@Data
@Accessors(chain = true)
@Schema(description = "管理员改派结果（V3.1）")
public class ReassignResultVO {

    @Schema(description = "订单 ID", example = "1001")
    private Long orderId;

    @Schema(description = "改派前的陪诊员用户 ID；此前无人接单时为 null", example = "null")
    private Long fromCompanionId;

    @Schema(description = "改派后的陪诊员用户 ID", example = "302")
    private Long toCompanionId;

    @Schema(description = "改派后的履约风险等级；本操作不改状态，等级由扫描任务维护", example = "URGENT")
    private String fulfillmentRisk;

    public static ReassignResultVO of(Long orderId, Long fromCompanionId,
                                      Long toCompanionId, String fulfillmentRisk) {
        return new ReassignResultVO()
                .setOrderId(orderId)
                .setFromCompanionId(fromCompanionId)
                .setToCompanionId(toCompanionId)
                .setFulfillmentRisk(fulfillmentRisk);
    }
}
