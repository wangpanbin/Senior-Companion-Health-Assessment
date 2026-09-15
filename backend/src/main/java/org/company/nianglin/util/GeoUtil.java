package org.company.nianglin.util;

import java.math.BigDecimal;

/**
 * 地理距离工具（一期只做距离校验，<b>不做地图渲染</b>）。
 *
 * <p>「一期不做地图导航」（计划书范围控制）把定位能力的用途收敛成了一句话：
 * 判断陪诊员打卡时「人是不是真的到了」。所以这里只需要一个球面距离函数，
 * 不需要任何瓦片、路径规划或逆地理编码。</p>
 *
 * <h3>为什么用 Haversine 而不是平面勾股</h3>
 *
 * <p>在纬度 20° 附近（海口），1 度经度约 104 km，1 度纬度约 111 km，
 * 差异只有 6%，看起来用平面近似也过得去。但那是「两点相距不远」时的错觉：
 * 经度方向的换算系数随纬度变化，一旦把服务范围扩到北方城市，
 * 平面近似的误差会被放大到足以让「有没有到医院」判断错误。
 * Haversine 的额外成本只是一次三角函数，没有理由省。</p>
 *
 * <p>返回值单位是<b>米</b>，因为阈值配置（{@code checkin-max-distance-meters}）
 * 与 {@code order_checkin.distance} 列都以米为单位。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
public final class GeoUtil {

    /** 地球平均半径（米） */
    private static final double EARTH_RADIUS_METERS = 6_371_008.8;

    private GeoUtil() {
    }

    /**
     * 两点球面距离（米）。
     *
     * @param lon1 点 1 经度
     * @param lat1 点 1 纬度
     * @param lon2 点 2 经度
     * @param lat2 点 2 纬度
     * @return 距离（米，四舍五入到整数）；任一坐标为 {@code null} 时返回 {@code null}
     *         —— 「算不出来」和「距离为 0」是两件事，不能混成同一个值
     */
    public static Integer distanceMeters(BigDecimal lon1, BigDecimal lat1, BigDecimal lon2, BigDecimal lat2) {
        if (lon1 == null || lat1 == null || lon2 == null || lat2 == null) {
            return null;
        }
        return distanceMeters(lon1.doubleValue(), lat1.doubleValue(), lon2.doubleValue(), lat2.doubleValue());
    }

    /** 两点球面距离（米），基本类型重载 */
    public static int distanceMeters(double lon1, double lat1, double lon2, double lat2) {
        double radLat1 = Math.toRadians(lat1);
        double radLat2 = Math.toRadians(lat2);
        double deltaLat = radLat2 - radLat1;
        double deltaLon = Math.toRadians(lon2 - lon1);

        double sinLat = Math.sin(deltaLat / 2);
        double sinLon = Math.sin(deltaLon / 2);
        double a = sinLat * sinLat + Math.cos(radLat1) * Math.cos(radLat2) * sinLon * sinLon;
        // clamp 是为了兜住浮点误差导致的 a 略大于 1（否则 asin 会得到 NaN）
        double c = 2 * Math.asin(Math.min(1.0, Math.sqrt(a)));
        return (int) Math.round(EARTH_RADIUS_METERS * c);
    }

    /**
     * 坐标是否落在合法取值范围内。
     *
     * <p>前端传入的字符串经 {@code BigDecimal} 解析后仍可能是 {@code 999}，
     * 让它进库没有任何好处，只会在日后统计时变成无法解释的脏点。</p>
     */
    public static boolean isValidCoordinate(BigDecimal longitude, BigDecimal latitude) {
        if (longitude == null || latitude == null) {
            return false;
        }
        return longitude.compareTo(BigDecimal.valueOf(-180)) >= 0
                && longitude.compareTo(BigDecimal.valueOf(180)) <= 0
                && latitude.compareTo(BigDecimal.valueOf(-90)) >= 0
                && latitude.compareTo(BigDecimal.valueOf(90)) <= 0;
    }
}
