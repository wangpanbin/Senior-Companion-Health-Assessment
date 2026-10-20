package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 今日用药计数（V3.2 · 家庭健康首页 / 老人端健康中心共用）。
 *
 * <h3>为什么单独抽一个 VO</h3>
 *
 * <p>家属端的「今日用药 x/y」与老人端的「今日已服药 / 还有 N 次没吃」问的是<b>同一件事</b> ——
 * 同一个口径就该有同一个类型。两处各写一个结构相同的类，迟早会分叉
 * （这正是 V3.2 修掉的用药三桶漂移的同款病因）。</p>
 *
 * <h3>「今日」不是统计区间</h3>
 *
 * <p>它按 {@code plan_date = 今天} 精确匹配，<b>不随统计区间浮动</b> ——
 * 「本月用药完成率」与「今天吃了 2 次」是两个维度，混在一张卡上会让人误以为是同一口径。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@Data
@Accessors(chain = true)
@Schema(description = "今日用药计数（按 plan_date 精确匹配当天，不随统计区间浮动）")
public class MedicationTodayVO {

    @Schema(description = "今日任务总数", example = "3")
    private Integer total;

    @Schema(description = "今日已服数量", example = "2")
    private Integer taken;

    @Schema(description = "今日漏服数量", example = "0")
    private Integer missed;

    @Schema(description = "今日待服数量", example = "1")
    private Integer pending;
}
