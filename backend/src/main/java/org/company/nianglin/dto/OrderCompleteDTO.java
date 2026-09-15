package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.company.nianglin.constant.ValidationPatterns;

import java.math.BigDecimal;
import java.util.List;

/**
 * 完成服务入参。
 *
 * <p>对应 {@code POST /api/order/{id}/complete}（{@code docs/api/03-order.md} §9）。</p>
 *
 * <h3>{@code summary} 是本模块的合规红线落点</h3>
 *
 * <p>服务小结只允许记录<b>过程</b>（几点到院、是否取药、老人精神状态如何），
 * 不允许出现诊断结论与用药建议。校验由
 * {@code ComplianceCheckUtil} 承担，命中即返回 {@code 3007}。
 * 这也不是「文档里写一句请大家自觉」就算完的事 —— 一条违规内容发出去就收不回来了。</p>
 *
 * <p>三个字段都可空：陪诊员可能只想先把订单走完，事后补填记录。
 * 但一旦填写，就必须合法。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
@Data
@Schema(description = "完成服务入参")
public class OrderCompleteDTO {

    @Schema(description = "服务小结（只记录过程，禁止出现诊断与用药建议）",
            example = "09:10 到达医院，全程陪同完成心血管内科就诊，协助缴费与取药，11:50 送老人回家")
    @Size(max = 500, message = "服务小结不能超过 500 个字符")
    private String summary;

    @Schema(description = "现场 / 取药凭证照片 URL 列表，最多 6 张",
            example = "[\"/uploads/202609/abc.jpg\"]")
    @Size(max = 6, message = "服务照片最多 6 张")
    private List<@Pattern(regexp = ValidationPatterns.FILE_URL, message = "照片地址格式不正确") String> photos;

    @Schema(description = "实际结算服务费（线下结算记录，不传则以订单服务费为准）", example = "128.00")
    @DecimalMin(value = "0.00", message = "实际结算金额不能为负数")
    @Digits(integer = 8, fraction = 2, message = "实际结算金额最多两位小数")
    private BigDecimal fee;
}
