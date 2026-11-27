package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 位置上报入参（{@code POST /api/execution/{orderId}/location}，ADR-0022）。
 *
 * <p>对应 {@code docs/api/04-companion-execution.md} §7。</p>
 *
 * <h3>坐标系：一律 WGS84，服务端不做任何转换</h3>
 *
 * <p>这里是浏览器 {@code navigator.geolocation} 的原生值，与
 * {@code companion_track} / {@code order_checkin} / {@code companion_order} 同一口径。
 * 百度地图要 BD09，<b>转换在前端渲染前完成</b>（{@code utils/coordConvert.js}）。</p>
 *
 * <p>反向做（落库改存 BD09）会让打卡 {@code 4001} 的 2000 米阈值凭空多出约 500 米偏差，
 * 且存量数据无标记、无法区分新旧口径 —— 判定错了是看不出来的，渲染错了才看得出来。</p>
 *
 * <h3>经纬度收字符串</h3>
 *
 * <p>理由与 {@link CheckinCreateDTO} 相同：避免 JSON 数字先过 {@code double}
 * 再落 {@code DECIMAL(10,6)} 时产生 {@code 110.33119999} 这种尾巴。</p>
 *
 * @author 银龄伴诊团队
 * @since ADR-0022
 */
@Data
@Schema(description = "位置上报入参（WGS84）")
public class LocationReportDTO {

    @Schema(description = "当前经度（WGS84），保留 6 位小数", example = "110.331200",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "经度不能为空")
    private String longitude;

    @Schema(description = "当前纬度（WGS84），保留 6 位小数", example = "20.031500",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "纬度不能为空")
    private String latitude;

    @Schema(description = "定位精度（米），写入 companion_track.accuracy；不做逆地理编码，也不参与任何判定",
            example = "18.5")
    @DecimalMin(value = "0", message = "定位精度不能为负数")
    private Double accuracy;

    @Schema(description = "速度（米/秒），写入 companion_track.speed；仅作轨迹辅助信息", example = "1.2")
    @DecimalMin(value = "0", message = "速度不能为负数")
    private Double speed;

    /** 经度转 {@link BigDecimal}；调用方已保证非空 */
    public BigDecimal longitudeValue() {
        return new BigDecimal(this.longitude.trim());
    }

    /** 纬度转 {@link BigDecimal}；调用方已保证非空 */
    public BigDecimal latitudeValue() {
        return new BigDecimal(this.latitude.trim());
    }
}
