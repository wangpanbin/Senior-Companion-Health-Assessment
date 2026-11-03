package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.common.DateRange;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 体征趋势（V4 · P2-2）。
 *
 * <p>{@code points} 按测量时间升序；区间内超过 500 条时取<b>最近 500 条</b>，
 * 并置 {@code truncated=true} —— 必须如实告诉调用方「这里没有全部返回」。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P2-2
 */
@Data
@Accessors(chain = true)
@Schema(description = "体征趋势")
public class VitalTrendVO {

    @Schema(description = "体征类型：BLOOD_PRESSURE / BLOOD_GLUCOSE / WEIGHT", example = "BLOOD_PRESSURE")
    private String vitalType;

    @Schema(description = "体征类型展示名", example = "血压")
    private String vitalTypeLabel;

    @Schema(description = "展示单位", example = "mmHg")
    private String unit;

    @Schema(description = "区间")
    private Range range;

    @Schema(description = "返回点数", example = "12")
    private Integer pointCount;

    @Schema(description = "是否因超出 500 点上限而被截断", example = "false")
    private Boolean truncated;

    @Schema(description = "趋势点，按测量时间升序；无数据时为空数组")
    private List<VitalTrendPointVO> points = new ArrayList<>();

    /** 区间信息 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "趋势区间")
    public static class Range {

        @Schema(description = "区间开始日期（含）", example = "2026-10-05")
        private LocalDate startDate;

        @Schema(description = "区间结束日期（含）", example = "2026-11-03")
        private LocalDate endDate;

        @Schema(description = "区间天数（含首尾）", example = "30")
        private Integer days;
    }

    /**
     * 组装区间信息。
     *
     * @param range 已校验的区间
     * @return 区间 VO；入参为 {@code null} 时返回 {@code null}
     */
    public static Range rangeOf(DateRange range) {
        if (range == null) {
            return null;
        }
        return new Range()
                .setStartDate(range.start())
                .setEndDate(range.end())
                .setDays((int) range.days());
    }
}
