package org.company.nianglin.service.support.matching;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 被匹配对象（订单）的快照（V3.4 · FR-Rule）。
 *
 * <p>与 {@link MatchingCandidate} 一样是<b>快照</b>而不是实体 ——
 * 规则只认快照，因此规则是纯函数。</p>
 *
 * @param orderId     订单 ID
 * @param regionCode  订单服务区域码；未映射时 {@code null}
 * @param address     订单地址自由文本（结构化列缺失时的回退匹配依据）
 * @param visitTime   就诊时间
 * @param longitude   订单坐标经度
 * @param latitude    订单坐标纬度
 * @param department  就诊科室
 * @author 银龄伴诊团队
 * @since V3.4
 */
public record MatchTarget(
        Long orderId,
        String regionCode,
        String address,
        LocalDateTime visitTime,
        BigDecimal longitude,
        BigDecimal latitude,
        String department) {

    /** 订单有没有坐标 */
    public boolean hasCoordinates() {
        return longitude != null && latitude != null;
    }

    /** 订单有没有区域码 */
    public boolean hasRegionCode() {
        return regionCode != null && !regionCode.isBlank();
    }
}
