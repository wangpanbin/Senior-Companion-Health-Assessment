package org.company.nianglin.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 距离计算单测（M5 打卡有效性判定的底座）。
 *
 * <p>打卡「人到没到」全靠这个数，因此这里锁的是<b>量级</b>而不是精确值：
 * 只要误差在 1% 以内，2000 米的阈值判断就不会反转。
 * 用真实城市坐标做基准点，比构造抽象数字更能暴露「经纬度传反了」这类错误。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@DisplayName("地理距离：Haversine / 坐标合法性")
class GeoUtilTest {

    /** 海南省人民医院 */
    private static final double LON = 110.331200;
    private static final double LAT = 20.031500;

    @Test
    @DisplayName("同一点距离为 0")
    void samePointIsZero() {
        assertEquals(0, GeoUtil.distanceMeters(LON, LAT, LON, LAT));
    }

    @Test
    @DisplayName("纬度差 1 度约 111 km（±1%）")
    void oneDegreeLatitudeIsAbout111Km() {
        int meters = GeoUtil.distanceMeters(LON, LAT, LON, LAT + 1);
        assertTrue(meters > 110_000 && meters < 112_000, "实际 " + meters + " 米");
    }

    @Test
    @DisplayName("经度差 1 度在北纬 20° 约 104 km（平面近似会算成 111 km，故必须走球面）")
    void oneDegreeLongitudeAccountsForLatitude() {
        int meters = GeoUtil.distanceMeters(LON, LAT, LON + 1, LAT);
        assertTrue(meters > 103_000 && meters < 105_000, "实际 " + meters + " 米");
    }

    @Test
    @DisplayName("往返对称：A→B 与 B→A 距离相同")
    void distanceIsSymmetric() {
        int forward = GeoUtil.distanceMeters(LON, LAT, 109.508000, 18.247000);
        int backward = GeoUtil.distanceMeters(109.508000, 18.247000, LON, LAT);
        assertEquals(forward, backward);
    }

    @Test
    @DisplayName("海口 → 三亚约 216 km（量级校验，球面大圆距离）")
    void haikouToSanyaIsAbout200Km() {
        int meters = GeoUtil.distanceMeters(110.331200, 20.031500, 109.508000, 18.247000);
        assertTrue(meters > 210_000 && meters < 225_000, "实际 " + meters + " 米");
    }

    @Test
    @DisplayName("任一坐标为 null 时返回 null，而不是退化成 0（「算不出来」≠「距离为 0」）")
    void nullCoordinateReturnsNull() {
        assertNull(GeoUtil.distanceMeters(null, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        assertNull(GeoUtil.distanceMeters(BigDecimal.ONE, null, BigDecimal.ONE, BigDecimal.ONE));
        assertNull(GeoUtil.distanceMeters(BigDecimal.ONE, BigDecimal.ONE, null, BigDecimal.ONE));
    }

    @Test
    @DisplayName("BigDecimal 重载保留 6 位小数精度")
    void bigDecimalOverloadKeepsPrecision() {
        Integer meters = GeoUtil.distanceMeters(
                new BigDecimal("110.331200"), new BigDecimal("20.031500"),
                new BigDecimal("110.331200"), new BigDecimal("20.031500"));
        assertEquals(0, meters);
    }

    @Test
    @DisplayName("坐标合法性边界：±180 / ±90 合法，越界非法，null 非法")
    void coordinateValidation() {
        assertTrue(GeoUtil.isValidCoordinate(new BigDecimal("180"), new BigDecimal("90")));
        assertTrue(GeoUtil.isValidCoordinate(new BigDecimal("-180"), new BigDecimal("-90")));
        assertTrue(GeoUtil.isValidCoordinate(new BigDecimal("110.331200"), new BigDecimal("20.031500")));
        assertFalse(GeoUtil.isValidCoordinate(new BigDecimal("180.000001"), new BigDecimal("20")));
        assertFalse(GeoUtil.isValidCoordinate(new BigDecimal("110"), new BigDecimal("90.5")));
        assertFalse(GeoUtil.isValidCoordinate(null, new BigDecimal("20")));
    }
}
