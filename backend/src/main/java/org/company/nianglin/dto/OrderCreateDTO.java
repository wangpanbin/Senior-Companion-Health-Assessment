package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
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
 * <h3>为什么必须收医院经纬度</h3>
 *
 * <p>{@code companion_order} 表早就有 {@code longitude / latitude} 两列（种子数据是填了的），
 * 但下单接口原先不接收它们，于是<b>每一张用户真实下的单都被写成坐标 NULL</b>。
 * 而 M5 打卡的距离校验是
 * {@code GeoUtil.distanceMeters(打卡点, 订单坐标)}，订单坐标为 NULL 时该计算返回
 * {@code null}，校验被静默跳过 —— 结果是「打卡坐标超出阈值 → 4001」这条验收标准
 * 在任何真实订单上都永远不可能触发，看似实现了、实际是死代码。</p>
 *
 * <p>两列都可选（一期不做地图导航，不强制前端取定位），但<b>建议传</b>：
 * 没有坐标的订单，陪诊员打卡时距离恒为 {@code null}，
 * 「是否到达陪诊地点」这件事就没有任何数据可以判定。</p>
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

    @Schema(description = "医院经度；不传则本单不做打卡距离校验（建议传，否则陪诊员的"
            + "「是否到达陪诊地点」将无法判定）", example = "110.311200")
    @DecimalMin(value = "-180.00", message = "经度取值范围为 -180 ~ 180")
    @DecimalMax(value = "180.00", message = "经度取值范围为 -180 ~ 180")
    @Digits(integer = 3, fraction = 6, message = "经度最多保留 6 位小数")
    private BigDecimal longitude;

    @Schema(description = "医院纬度；与经度成对出现，只传一个视为未传", example = "20.021500")
    @DecimalMin(value = "-90.00", message = "纬度取值范围为 -90 ~ 90")
    @DecimalMax(value = "90.00", message = "纬度取值范围为 -90 ~ 90")
    @Digits(integer = 2, fraction = 6, message = "纬度最多保留 6 位小数")
    private BigDecimal latitude;

    @Schema(description = "备注，如「老人听力不好，请大声沟通」", example = "需协助取药")
    @Size(max = 500, message = "备注不能超过 500 个字符")
    private String remark;

    @Schema(description = "服务费；不传则由后端按规则计算（工作日白天 128.00，夜间或周末 158.00）",
            example = "128.00")
    @DecimalMin(value = "0.00", message = "服务费不能为负数")
    @Digits(integer = 8, fraction = 2, message = "服务费最多两位小数")
    private BigDecimal fee;
}
