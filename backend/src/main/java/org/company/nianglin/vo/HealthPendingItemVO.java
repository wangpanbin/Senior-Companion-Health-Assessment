package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 待处理事项（V3.2 · FR-Overview）。
 *
 * <h3>只陈述事实与数量</h3>
 *
 * <p>例如「今日有 1 次服药任务未确认」「有 1 个订单在等待接单」，
 * <b>不出现</b>「老人今天健康状况较差」这类结论式表述 ——
 * 本项目不做诊断、不开药方（合规红线-1）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@Data
@Accessors(chain = true)
@Schema(description = "待处理事项")
public class HealthPendingItemVO {

    @Schema(description = "事项类型：MEDICATION_PENDING / ORDER_WAITING / ORDER_TO_REVIEW / FULFILLMENT_RISK",
            example = "ORDER_WAITING")
    private String type;

    @Schema(description = "事项中文名", example = "订单等待接单")
    private String typeLabel;

    @Schema(description = "数量", example = "1")
    private Integer count;

    @Schema(description = "处理入口链接（前端路由）", example = "/family/order/1001")
    private String linkUrl;
}
