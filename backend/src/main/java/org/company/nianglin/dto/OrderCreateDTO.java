package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 创建陪诊订单入参。
 *
 * <p>对应 {@code POST /api/order}（{@code docs/api/03-order.md} §1）。</p>
 *
 * <h3>没有 {@code familyId}，也没有 {@code status}</h3>
 *
 * <p>下单人一律取自令牌，订单初始状态由服务端写死为 {@code PENDING}。把
 * 「谁是下单人」交给前端决定，等于允许家属以别人的名义下单；把「初始状态」
 * 交给前端决定，等于允许直接下单成一笔「已完成」的订单去刷单。</p>
 *
 * <p>{@code fee} 允许不传：不传时由后端按 {@code OrderServiceImpl#resolveFee} 的
 * 规则计算，避免前端各自实现一套价格规则后对不上账。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
@Data
@Schema(description = "创建陪诊订单入参")
public class OrderCreateDTO {

    @Schema(description = "就诊老人档案 ID（必须是当前家属绑定的老人）", example = "401")
    @NotNull(message = "请选择就诊老人")
    private Long elderId;

    @Schema(description = "医院名称", example = "海南省人民医院")
    @NotBlank(message = "请填写医院名称")
    @Size(max = 100, message = "医院名称不能超过 100 个字符")
    private String hospital;

    @Schema(description = "就诊科室", example = "心血管内科")
    @NotBlank(message = "请填写就诊科室")
    @Size(max = 50, message = "就诊科室不能超过 50 个字符")
    private String department;

    @Schema(description = "就诊时间，必须晚于当前时间", example = "2026-09-20 09:30:00")
    @NotNull(message = "请选择就诊时间")
    private LocalDateTime visitTime;

    @Schema(description = "医院地址（文字地址，一期不做地图导航）",
            example = "海南省海口市秀英区秀华路19号 门诊大楼3楼")
    @NotBlank(message = "请填写医院地址")
    @Size(max = 200, message = "医院地址不能超过 200 个字符")
    private String address;

    @Schema(description = "备注，如「老人听力不好，请大声沟通」", example = "需协助取药")
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;

    @Schema(description = "服务费；不传则由后端按规则计算（工作日白天 128.00，夜间或周末 158.00）",
            example = "128.00")
    @DecimalMin(value = "0.00", message = "服务费不能为负数")
    @Digits(integer = 8, fraction = 2, message = "服务费最多两位小数")
    private BigDecimal fee;
}
