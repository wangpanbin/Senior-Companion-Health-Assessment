package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDate;

/**
 * 总览指标（{@code docs/api/09-statistics-export.md} §1）。
 *
 * <h3>比率的口径写在这里，不藏在 SQL 里</h3>
 *
 * <p>{@code completedRate} 的分母是<b>区间内全部订单（含已取消）</b>，
 * 而不是「已完成 + 已取消」。这个选择必须显式写出来，因为答辩时一定有人问
 * 「为什么完成率只有 86%，剩下 14% 是什么」——答案必须是
 * 「剩下的就是未完成与已取消，这个指标衡量的是全量订单的完成占比」，
 * 而不是「我也不知道分母算的哪一批」。</p>
 *
 * <p>另一个容易错的口径：{@code COMPLETED} 与 {@code REVIEWED}
 * <b>都算已完成</b>。评价只是完成之后的后续动作，把已评价的单排除在
 * 「已完成」之外，会让完成率随着用户评价而<b>下降</b>。</p>
 *
 * @author 银龄伴诊团队
 * @since M10
 */
@Data
@Accessors(chain = true)
@Schema(description = "总览指标")
public class StatisticsOverviewVO {

    @Schema(description = "统计开始日期", example = "2026-08-16")
    private LocalDate startDate;

    @Schema(description = "统计结束日期", example = "2026-09-15")
    private LocalDate endDate;

    @Schema(description = "订单指标")
    private Order order;

    @Schema(description = "用户指标")
    private User user;

    @Schema(description = "陪诊员指标")
    private Companion companion;

    @Schema(description = "用药指标")
    private Medication medication;

    /** 订单指标 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "订单指标")
    public static class Order {

        @Schema(description = "区间内订单总数", example = "137")
        private Integer totalCount;

        @Schema(description = "已完成（含已评价）", example = "118")
        private Integer completedCount;

        @Schema(description = "已取消", example = "9")
        private Integer cancelledCount;

        @Schema(description = "进行中（待接单 / 已接单 / 服务中）", example = "10")
        private Integer inProgressCount;

        @Schema(description = "完成率 = 已完成 / 区间内全部订单", example = "86.13%")
        private String completedRate;

        @Schema(description = "取消率 = 已取消 / 区间内全部订单", example = "6.57%")
        private String cancelRate;
    }

    /** 用户指标 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "用户指标")
    public static class User {

        @Schema(description = "用户总数（全量，不受区间影响）", example = "213")
        private Integer totalCount;

        @Schema(description = "区间内新增用户数", example = "18")
        private Integer newCount;

        @Schema(description = "区间内增长率 = 新增 / （总数 - 新增）", example = "9.23%")
        private String growthRate;

        @Schema(description = "老年患者数", example = "64")
        private Integer elderCount;

        @Schema(description = "家属数", example = "121")
        private Integer familyCount;

        @Schema(description = "陪诊员数", example = "26")
        private Integer companionCount;

        @Schema(description = "管理员数", example = "2")
        private Integer adminCount;
    }

    /** 陪诊员指标 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "陪诊员指标")
    public static class Companion {

        @Schema(description = "区间内有接单的陪诊员数", example = "17")
        private Integer activeCount;

        @Schema(description = "平均接单耗时（分钟，下单 → 接单）；无数据时返回 0", example = "12")
        private Integer avgAcceptMinutes;
    }

    /** 用药指标 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "用药指标")
    public static class Medication {

        @Schema(description = "服药任务总数", example = "1580")
        private Integer taskTotalCount;

        @Schema(description = "已服用", example = "1421")
        private Integer takenCount;

        @Schema(description = "漏服（含漏服后补记）", example = "89")
        private Integer missedCount;

        @Schema(description = "漏服率 = 漏服 / 任务总数", example = "5.63%")
        private String missedRate;
    }
}
