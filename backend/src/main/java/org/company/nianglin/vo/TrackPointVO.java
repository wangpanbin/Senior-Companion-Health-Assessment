package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.entity.CompanionTrack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/**
 * 轨迹点（{@code docs/api/04-companion-execution.md} §一 TrackPointVO）。
 *
 * <p><b>一期不渲染地图</b>，本对象的数据用途写在文档里且只有三个：
 * 生成「陪诊路径文字摘要」、异常打卡申诉取证、答辩时展示轨迹原始数据。</p>
 *
 * <p>因此这里刻意<b>不返回</b> {@code accuracy} / {@code speed} /
 * {@code companionId} —— 定位精度与速度是「剔除漂移点」用的内部指标，
 * 放出来只会让前端以为需要自己再做一次过滤。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@Data
@Accessors(chain = true)
@Schema(description = "陪诊轨迹点")
public class TrackPointVO {

    @Schema(description = "经度（字符串，保留 6 位小数）", example = "110.311200")
    private String longitude;

    @Schema(description = "纬度（字符串，保留 6 位小数）", example = "20.021500")
    private String latitude;

    @Schema(description = "关联的打卡节点，非打卡产生的点为 null", example = "DEPART")
    private String node;

    @Schema(description = "记录时间", example = "2026-09-20 08:20:00")
    private LocalDateTime recordTime;

    public static TrackPointVO of(CompanionTrack row) {
        if (row == null) {
            return null;
        }
        return new TrackPointVO()
                .setLongitude(plain(row.getLongitude()))
                .setLatitude(plain(row.getLatitude()))
                .setNode(row.getNode())
                .setRecordTime(row.getRecordTime());
    }

    private static String plain(BigDecimal value) {
        return value == null ? null : value.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }
}
