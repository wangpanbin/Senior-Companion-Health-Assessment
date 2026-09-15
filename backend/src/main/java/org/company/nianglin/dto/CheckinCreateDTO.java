package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 打卡入参（{@code POST /api/execution/{orderId}/checkin}）。
 *
 * <p>对应 {@code docs/api/04-companion-execution.md} §1。</p>
 *
 * <h3>经纬度收字符串而不是 double</h3>
 *
 * <p>文档把经纬度定义成字符串（{@code "110.331200"}）就是为了避免 JS 的
 * {@code Number} 精度问题。JSON 里的数字先被解析成 {@code double}，
 * 再写进 {@code DECIMAL(10,6)} 时容易出现 {@code 110.33119999} 这种尾巴。
 * Jackson 能直接把 JSON 字符串绑定到 {@code BigDecimal}，全程不经过二进制浮点。</p>
 *
 * <h3>这里没有 {@code companionId}</h3>
 *
 * <p>打卡人是谁由令牌决定。<b>「谁来打卡」不是前端能选的事</b> ——
 * 一旦允许传入，任何一个陪诊员都能替别人打卡，距离校验也就失去意义了。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@Data
@Schema(description = "陪诊打卡入参")
public class CheckinCreateDTO {

    @Schema(description = "打卡节点：DEPART/ARRIVE/IN_CONSULT/TAKE_MEDICINE/LEAVE/FINISH",
            example = "ARRIVE", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "打卡节点不能为空")
    @Size(max = 20, message = "打卡节点取值不合法")
    private String node;

    @Schema(description = "当前经度，保留 6 位小数", example = "110.331200",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "经度不能为空")
    private String longitude;

    @Schema(description = "当前纬度，保留 6 位小数", example = "20.031500",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "纬度不能为空")
    private String latitude;

    @Schema(description = "当前位置描述（一期不做逆地理编码，由前端传入）", example = "海南省人民医院 门诊大楼")
    @Size(max = 200, message = "位置描述不能超过 200 个字符")
    private String address;

    @Schema(description = "现场照片 URL 列表，最多 6 张")
    @Size(max = 6, message = "现场照片最多 6 张")
    private List<String> photos;

    @Schema(description = "备注", example = "已到达医院，正在取号")
    @Size(max = 200, message = "备注不能超过 200 个字符")
    private String remark;

    /* ------------------------------------------------------------------ */
    /* 便捷访问器                                                          */
    /* ------------------------------------------------------------------ */

    /** 经度转 {@link BigDecimal}；调用方已保证非空 */
    public BigDecimal longitudeValue() {
        return new BigDecimal(this.longitude.trim());
    }

    /** 纬度转 {@link BigDecimal}；调用方已保证非空 */
    public BigDecimal latitudeValue() {
        return new BigDecimal(this.latitude.trim());
    }
}
