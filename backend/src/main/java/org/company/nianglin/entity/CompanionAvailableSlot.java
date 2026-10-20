package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;

import java.time.LocalTime;

/**
 * CompanionAvailableSlot —— 对应表 {@code companion_available_slot}。
 *
 * <p>陪诊员可服务时段（V3.4 结构化改造）。</p>
 *
 * <p>主键与审计字段（id / createTime / updateTime / deleted）继承自 {@link BaseEntity}。</p>
 *
 * <h3>为什么用表而不是 JSON 列</h3>
 *
 * <p>可服务时段要参与筛选（订单就诊时间落在哪个时段里），
 * 必须可索引、可 JOIN。JSON 列做不到「按星期 + 时刻范围」的高效筛选，
 * 而 V3.4 的时间匹配维度恰恰要按这个条件过滤候选。</p>
 *
 * @since V3.4
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("companion_available_slot")
public class CompanionAvailableSlot extends BaseEntity {

    /**
     * 陪诊员用户 ID
     */
    @Schema(description = "陪诊员用户 ID")
    private Long companionId;

    /**
     * 周内第几天：1=周一 … 7=周日（与 Java WeekFields.ISO 对齐）
     */
    @Schema(description = "周内第几天：1=周一 … 7=周日", example = "1")
    private Integer weekday;

    /**
     * 可服务起始时刻
     */
    @Schema(description = "可服务起始时刻", example = "08:00:00")
    private LocalTime startTime;

    /**
     * 可服务结束时刻
     */
    @Schema(description = "可服务结束时刻", example = "18:00:00")
    private LocalTime endTime;
}
