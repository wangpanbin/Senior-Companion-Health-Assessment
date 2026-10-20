package org.company.nianglin.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 异常中心的一个条目（V3.5 · T3）。
 *
 * <h3>为什么 {@code referenceId} 强制输出成 {@code null}</h3>
 *
 * <p>全局 Jackson 策略是 {@code default-property-inclusion: non_null}，
 * {@code null} 字段会被整个省略。本字段用 {@code ALWAYS} 覆盖，
 * 是为了让「这一类没有可定位的对象」与「这一类没返回这个字段」在响应里可区分 ——
 * 前端据此决定要不要显示「定位」入口。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.5
 */
@Data
@Accessors(chain = true)
@Schema(description = "异常中心条目")
public class OperationsExceptionItemVO {

    @Schema(description = "异常类型：MANUAL_REQUIRED / MATCH_WARNING / COMPANION_COMPLAINT_RATE / MEDICATION_MISSED",
            example = "MANUAL_REQUIRED")
    private String type;

    @Schema(description = "异常类型中文名", example = "需人工介入订单")
    private String typeLabel;

    @Schema(description = "数量", example = "3")
    private Integer count;

    @Schema(description = "严重级别：DANGER / WARNING / INFO", example = "DANGER")
    private String level;

    @Schema(description = "处理入口路由（必须是前端真实存在的页面）", example = "/admin/fulfillment")
    private String linkUrl;

    @Schema(description = "可定位的 ID；无可定位对象时为 null", example = "1019")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private Long referenceId;

    @Schema(description = "一句话说明", example = "履约风险已达人工介入阈值，需要管理员协调")
    private String description;
}
