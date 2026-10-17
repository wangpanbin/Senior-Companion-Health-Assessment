package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 家属催单的入参（V3.1 · T3）。
 *
 * <p><b>整个请求体是可选的</b>：催单本身不需要带任何字段，{@code remark} 只是
 * 家属想补充的一句话。因此没有 {@code @NotNull}，控制器侧也不加 {@code @Valid}
 * 之外的必填约束。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@Data
@Accessors(chain = true)
@Schema(description = "家属催促陪诊员接单（V3.1）")
public class OrderUrgeDTO {

    @Size(max = 200, message = "备注不能超过 200 字")
    @Schema(description = "可选备注，会随履约事件一并留痕；**写入前过合规词表**",
            example = "老人明天早上八点半到，需要赶时间")
    private String remark;
}
