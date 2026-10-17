package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 陪诊员切换接单状态的入参（V3.1 · T0）。
 *
 * <p><b>为什么用 {@link String} 而不是直接接枚举：</b>
 * 若字段声明为 {@code WorkStatus}，非法字面量会在 Jackson 反序列化阶段就抛
 * {@code HttpMessageNotReadableException}，用户看到的是一段框架堆栈提示，
 * 且无法区分「你填错了」和「接口坏了」。这里收 String、在 Service 里显式解析，
 * 才能给出「接单状态只能是 AVAILABLE / REST / OFFLINE」这种能指导用户改正的话。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@Data
@Accessors(chain = true)
@Schema(description = "切换陪诊员接单状态")
public class CompanionWorkStatusUpdateDTO {

    @NotBlank(message = "接单状态不能为空")
    @Schema(description = "接单状态：AVAILABLE-可接单 / REST-休息中 / OFFLINE-离线",
            example = "REST",
            allowableValues = {"AVAILABLE", "REST", "OFFLINE"})
    private String workStatus;
}
