package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.CheckinNode;
import org.company.nianglin.entity.OrderCheckin;
import org.company.nianglin.util.MaskUtil;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 打卡记录（{@code docs/api/04-companion-execution.md} §一 CheckinVO）。
 *
 * <p>用于 {@code GET /api/execution/{orderId}/checkins} 的时间线渲染。</p>
 *
 * <h3>两处刻意返回字符串</h3>
 *
 * <ul>
 *   <li><b>经纬度</b>：文档示例是 {@code "110.331200"}。用字符串能保住末尾的 0，
 *       前端直接展示即可，不用自己补位。</li>
 *   <li><b>打卡人姓名</b>：脱敏成 {@code 李*}。时间线是给家属看的，
 *       家属本来就知道陪诊员是谁，没必要在列表里给全名 ——
 *       而列表是最容易被旁人扫到的一屏。</li>
 * </ul>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@Data
@Accessors(chain = true)
@Schema(description = "陪诊打卡记录")
public class CheckinVO {

    @Schema(description = "打卡记录 ID", example = "8001")
    private Long id;

    @Schema(description = "订单 ID", example = "1001")
    private Long orderId;

    @Schema(description = "打卡节点枚举名", example = "ARRIVE")
    private String node;

    @Schema(description = "打卡节点中文", example = "到院")
    private String nodeLabel;

    @Schema(description = "经度（字符串，保留 6 位小数）", example = "110.331200")
    private String longitude;

    @Schema(description = "纬度（字符串，保留 6 位小数）", example = "20.031500")
    private String latitude;

    @Schema(description = "打卡位置文字描述", example = "海南省人民医院 门诊大楼")
    private String address;

    @Schema(description = "与订单地址的直线距离（米）；订单未登记坐标时为 null", example = "128")
    private Integer distance;

    @Schema(description = "是否异常打卡（超出阈值）", example = "false")
    private Boolean isAbnormal;

    @Schema(description = "现场照片 URL 列表")
    private List<String> photos;

    @Schema(description = "打卡人用户 ID", example = "301")
    private Long operatorId;

    @Schema(description = "打卡人姓名（脱敏）", example = "李*")
    private String operatorName;

    @Schema(description = "打卡时间", example = "2026-09-20 09:10:00")
    private LocalDateTime checkinTime;

    /**
     * @param operatorName 打卡人姓名原文，本方法内部脱敏
     * @param photos       已解析的照片列表（JSON 列的解析由调用方完成，失败传 {@code null}）
     */
    public static CheckinVO of(OrderCheckin row, String operatorName, List<String> photos) {
        if (row == null) {
            return null;
        }
        return new CheckinVO()
                .setId(row.getId())
                .setOrderId(row.getOrderId())
                .setNode(row.getNode())
                .setNodeLabel(CheckinNode.labelOf(row.getNode()))
                .setLongitude(plain(row.getLongitude()))
                .setLatitude(plain(row.getLatitude()))
                .setAddress(row.getAddress())
                .setDistance(row.getDistance())
                .setIsAbnormal(row.getIsAbnormal() != null && row.getIsAbnormal() == 1)
                .setPhotos(photos)
                .setOperatorId(row.getCompanionId())
                .setOperatorName(MaskUtil.name(operatorName))
                .setCheckinTime(row.getCheckinTime());
    }

    /** {@code DECIMAL(10,6)} → 六位小数字符串；空值保持 {@code null} */
    private static String plain(BigDecimal value) {
        return value == null ? null : value.setScale(6, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
