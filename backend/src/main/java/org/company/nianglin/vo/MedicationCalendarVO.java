package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.constant.MedicationTaskStatus;
import org.company.nianglin.util.MaskUtil;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服药日历（对外唯一出口）。
 *
 * <p>对应 {@code docs/api/05-medication.md} §7。响应结构是
 * 「区间元信息 + 汇总 + 按天分组」，而不是一个扁平的任务数组 ——
 * 日历 UI 需要知道「哪几天一条任务都没有」，
 * 平坦数组里缺的那天会被默认渲染成「无数据」还是「无任务」，
 * 前端必须再自己补一次日期轴。分组在服务端做一次，前端只负责渲染。</p>
 *
 * <h3>汇总口径必须与逐条记录对得上</h3>
 *
 * <p>{@code totalCount} 一定等于所有 {@code days[].tasks} 的长度之和，
 * 且 {@code takenCount + missedCount + pendingCount == totalCount} ——
 * 三个桶必须构成一个<b>划分</b>，每条任务恰好落进一个桶。</p>
 *
 * <p>划分规则（顺序不能调）：</p>
 * <ol>
 *   <li>{@code status = MISSED} 或 {@code was_missed = 1} → <b>漏服</b>。
 *       前者是「一直没吃被扫描判成漏服」，后者是「漏了之后被补记」
 *       （补记后 {@code status} 已变成 {@code TAKEN}，只有 {@code was_missed} 还留着痕迹）。
 *       两者都是事实上的漏服，必须一起统计，否则「漏服数」会漏掉其中一整类。</li>
 *   <li>{@code status = PENDING} → <b>待服</b></li>
 *   <li>其余（{@code TAKEN} 且从未漏过）→ <b>已服</b></li>
 * </ol>
 *
 * <p>⚠️ 这里以前写的是「{@code missedCount} 只取 {@code was_missed = 1}」，
 * 想表达「补记过的也算漏服」。方向对但做法错了 ——
 * 漏服扫描只把 {@code status} 置成 {@code MISSED}，从不写 {@code was_missed}，
 * 于是真实漏服（占绝大多数）一条都不进统计，反而只有补记的那几条被算成漏服，
 * 统计口径与事实正好相反；更直接的症状是
 * {@code taken + missed + pending < total}，数字自己就对不上账。
 * 现在两个条件取并集，划分重新闭合。</p>
 *
 * @author 银龄伴诊团队
 * @since M6
 */
@Data
@Accessors(chain = true)
@Schema(description = "服药日历")
public class MedicationCalendarVO {

    @Schema(description = "老人档案 ID", example = "301")
    private Long elderId;

    @Schema(description = "老人姓名（脱敏）", example = "张*三")
    private String elderName;

    @Schema(description = "区间开始日期", example = "2026-09-14")
    private LocalDate startDate;

    @Schema(description = "区间结束日期", example = "2026-09-20")
    private LocalDate endDate;

    @Schema(description = "汇总")
    private Summary summary;

    @Schema(description = "按天分组，只包含区间内实际有任务的日期，按日期升序")
    private List<Day> days = new ArrayList<>();

    /** 区间汇总 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "服药日历汇总")
    public static class Summary {

        @Schema(description = "区间内任务总数", example = "7")
        private Integer totalCount;

        @Schema(description = "已服数量", example = "5")
        private Integer takenCount;

        @Schema(description = "漏服数量（含漏服后补记的记录）", example = "1")
        private Integer missedCount;

        @Schema(description = "待服数量", example = "1")
        private Integer pendingCount;

        @Schema(description = "漏服率，两位小数百分比；总数为 0 时返回 0.00%", example = "14.29%")
        private String missedRate;
    }

    /** 某一天的任务集合 */
    @Data
    @Accessors(chain = true)
    @Schema(description = "服药日历中的一天")
    public static class Day {

        @Schema(description = "日期", example = "2026-09-15")
        private LocalDate date;

        @Schema(description = "当天的服药任务，按计划时间升序")
        private List<MedicationTaskVO> tasks = new ArrayList<>();
    }

    /**
     * 按 {@code planDate} 分组并计算汇总。
     *
     * @param tasks 区间内的任务（已按 planTime 升序），允许为空
     */
    public static MedicationCalendarVO of(Long elderId, String elderName,
                                          LocalDate startDate, LocalDate endDate,
                                          List<MedicationTaskVO> tasks) {
        List<MedicationTaskVO> list = tasks == null ? List.of() : tasks;

        Map<LocalDate, Day> grouped = new LinkedHashMap<>();
        int total = 0;
        int taken = 0;
        int missed = 0;
        int pending = 0;
        for (MedicationTaskVO task : list) {
            total++;
            // 三个桶的判定顺序 = 划分的优先级，不能调换：
            // 「曾经漏服」优先于「当前状态」，否则补记过的任务会被算进「已服」，
            // 漏服率立刻被稀释。
            String status = task.getStatus();
            boolean wasMissed = Boolean.TRUE.equals(task.getWasMissed());
            if (wasMissed || MedicationTaskStatus.MISSED.name().equals(status)) {
                missed++;
            } else if (MedicationTaskStatus.PENDING.name().equals(status)) {
                pending++;
            } else {
                taken++;
            }
            if (task.getPlanTime() != null) {
                LocalDate date = task.getPlanTime().toLocalDate();
                // 不能写成 Day::new —— Lombok 生成的是无参构造器，日期要显式回填
                grouped.computeIfAbsent(date, d -> new Day().setDate(d))
                        .getTasks().add(task);
            }
        }

        List<Day> days = new ArrayList<>(grouped.values());
        days.sort(Comparator.comparing(Day::getDate));

        Summary summary = new Summary()
                .setTotalCount(total)
                .setTakenCount(taken)
                .setMissedCount(missed)
                .setPendingCount(pending)
                .setMissedRate(rate(missed, total));

        return new MedicationCalendarVO()
                .setElderId(elderId)
                .setElderName(MaskUtil.name(elderName))
                .setStartDate(startDate)
                .setEndDate(endDate)
                .setSummary(summary)
                .setDays(days);
    }

    /**
     * 漏服率 = 漏服数 / 总数，保留两位小数百分比。
     *
     * <p>总数为 0 时返回 {@code "0.00%"} 而不是 {@code "NaN%"} 或 {@code null}：
     * 新加的计划在区间内可能一条任务都没有，这是正常状态而非异常，
     * 接口不该让前端为它写特例。</p>
     */
    private static String rate(int missed, int total) {
        if (total <= 0) {
            return "0.00%";
        }
        return BigDecimal.valueOf(missed)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP)
                .toPlainString() + "%";
    }
}
