package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 陪诊员最新位置（{@code docs/api/04-companion-execution.md} §7 响应 / §8 快照，ADR-0022）。
 *
 * <h3>{@code distanceMeters} 的语义必须说死</h3>
 *
 * <p>它是<b>陪诊员最新点 → 订单医院坐标</b>的直线距离（Haversine），
 * 与打卡 {@code 4001} 判定、匹配引擎 {@code DistanceMatchRule} 调用的是
 * <b>同一个</b> {@code GeoUtil.distanceMeters}。</p>
 *
 * <p>⚠️ 它<b>不是</b>「离老人住址的距离」—— {@code elder_profile} 表只有文字地址、
 * 没有坐标列。前端文案必须写「距医院直线约 x 公里」，
 * 写成「距老人」就是一句界面在说谎的话。</p>
 *
 * <p>订单未登记医院坐标时该字段为 {@code null}（不是 0）：
 * 「算不出来」和「距离为 0」是两件事，混成同一个值会让前端渲染出「已到达」。</p>
 *
 * @author 银龄伴诊团队
 * @since ADR-0022
 */
@Data
@Accessors(chain = true)
@Schema(description = "陪诊员最新位置（WGS84）")
public class LocationVO {

    @Schema(description = "订单 ID", example = "1001")
    private Long orderId;

    @Schema(description = "经度（WGS84，字符串，保留 6 位小数）；从未上报时为 null", example = "110.331200")
    private String longitude;

    @Schema(description = "纬度（WGS84，字符串，保留 6 位小数）；从未上报时为 null", example = "20.031500")
    private String latitude;

    @Schema(description = "定位精度（米）；未上报时为 null", example = "18.5")
    private Double accuracy;

    @Schema(description = "速度（米/秒）；未上报时为 null", example = "1.2")
    private Double speed;

    @Schema(description = "最后一次上报时间；从未上报时为 null", example = "2026-09-20 09:10:00")
    private LocalDateTime recordTime;

    @Schema(description = "与订单医院坐标的直线距离（米），与打卡 4001 同口径；"
            + "订单无坐标或从未上报时为 null", example = "1200")
    private Integer distanceMeters;

    @Schema(description = "医院经度（地图第二个标记）；订单未登记坐标时为 null", example = "110.311200")
    private String orderLongitude;

    @Schema(description = "医院纬度", example = "20.021500")
    private String orderLatitude;

    @Schema(description = "当前位置共享是否开启；暂停后家属端显示「已暂停共享」而不是冻住的点", example = "true")
    private Boolean sharingEnabled;

    @Schema(description = "距最后一次上报的秒数；由服务端盖章，不信客户端时钟。"
            + "超过 stale-threshold-seconds（默认 180）前端应标注「位置已 x 分钟未更新」", example = "30")
    private Long staleSeconds;

    /* ------------------------------------------------------------------ */
    /* 工厂方法                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 组装一条完整的位置快照。
     *
     * @param orderId         订单 ID
     * @param longitude       陪诊员经度（WGS84）；从未上报传 {@code null}
     * @param latitude        陪诊员纬度（WGS84）；从未上报传 {@code null}
     * @param accuracy        定位精度（米）
     * @param speed           速度（米/秒）
     * @param recordTime      最后一次上报时间
     * @param distanceMeters  与医院坐标的直线距离；算不出来传 {@code null}
     * @param orderLongitude  医院经度；订单未登记传 {@code null}
     * @param orderLatitude   医院纬度；订单未登记传 {@code null}
     * @param sharingEnabled  共享开关
     * @param staleSeconds    距最后一次上报的秒数
     */
    public static LocationVO of(Long orderId,
                               BigDecimal longitude, BigDecimal latitude,
                               Double accuracy, Double speed, LocalDateTime recordTime,
                               Integer distanceMeters,
                               BigDecimal orderLongitude, BigDecimal orderLatitude,
                               Boolean sharingEnabled, Long staleSeconds) {
        return new LocationVO()
                .setOrderId(orderId)
                .setLongitude(plain(longitude))
                .setLatitude(plain(latitude))
                .setAccuracy(accuracy)
                .setSpeed(speed)
                .setRecordTime(recordTime)
                .setDistanceMeters(distanceMeters)
                .setOrderLongitude(plain(orderLongitude))
                .setOrderLatitude(plain(orderLatitude))
                .setSharingEnabled(sharingEnabled)
                .setStaleSeconds(staleSeconds);
    }

    /** 坐标统一 6 位小数字符串：与 {@code DECIMAL(10,6)} 列一致，避免前端拿到 {@code 110.33119999} */
    private static String plain(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }
}
