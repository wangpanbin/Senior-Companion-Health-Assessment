package org.company.nianglin.constant;

import lombok.Getter;

import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.Locale;

/**
 * 统计时间粒度（{@code docs/api/09-statistics-export.md} §一）。
 *
 * <p>{@code granularity} = {@code DAY} / {@code WEEK} / {@code MONTH}。</p>
 *
 * <h3>为什么在 Java 侧聚合而不是 {@code GROUP BY DATE_FORMAT(...)}</h3>
 *
 * <p>数据库里只有 5 个 {@code DATE_FORMAT} 格式能表达日 / 周 / 月，
 * 而「补齐没有数据的日期为 0」这件事必须在这里做 ——
 * {@code GROUP BY} 只会返回有数据的桶，缺的那天在结果里<b>根本不存在</b>，
 * 前端拿到一个长度不定的数组，折线图就会跳段。</p>
 *
 * <p>既然「补齐」无论如何都要在 Java 侧做，就不必再让数据库做一次格式转换：
 * 只查「按天聚合的原始计数」（≤366 行），再在内存里合并到目标粒度。
 * 这样 WEEK / MONTH 的分桶规则（周一为起始）也只有一个落点。</p>
 *
 * <h3>周的分界</h3>
 *
 * <p>用 ISO 标准（周一为一周的第一天），因为国内的排班与周报都是这么算的。
 * {@code WeekFields.of(Locale.CHINA)} 与 ISO 在「周日算上周还是下周」
 * 上并不完全一致，这里显式用 ISO 以免出现「周日的数据凭空少了一天」。</p>
 *
 * @author 银龄伴诊团队
 * @since M10
 */
@Getter
public enum StatisticsGranularity {

    DAY("按日"),
    WEEK("按周"),
    MONTH("按月"),
    ;

    /** 中文展示名 */
    private final String label;

    StatisticsGranularity(String label) {
        this.label = label;
    }

    /** 按名称反查（忽略大小写），非法值返回 {@code null} 由调用方决定抛什么错 */
    public static StatisticsGranularity of(String name) {
        if (name == null) {
            return null;
        }
        for (StatisticsGranularity g : values()) {
            if (g.name().equalsIgnoreCase(name)) {
                return g;
            }
        }
        return null;
    }

    /**
     * 把日期折算成所在桶的「标签」。
     *
     * <p>WEEK 返回该周的周一（{@code yyyy-MM-dd}），MONTH 返回当月 1 日，
     * DAY 原样返回。用<b>桶的起始日</b>而不是「2026-W38」这类周序号作为
     * 分类名，是因为前端横轴需要一个能排序、能比对日期区间的字符串，
     * 而周序号的排序在跨年时会出错（W1 可能晚于上一年的 W52）。</p>
     */
    /**
     * 把日期折算成所在桶的<b>起始日</b>。
     *
     * <p>WEEK 返回该周的周一，MONTH 返回当月 1 日，DAY 原样返回。
     * 返回 {@code LocalDate} 而不是「2026-W38」这类周序号：分类轴的标签需要一个
     * 能排序、能比对区间的东西，而周序号在跨年时会排错
     * （当年的 W1 时间上晚于上一年的 W52，字符串比较却是 W1 更小）。</p>
     */
    public LocalDate bucketDate(LocalDate date) {
        return switch (this) {
            case DAY -> date;
            // ISO 周：周一是一周第一天（MONDAY=1…SUNDAY=7），
            // 让周日归入「本周一」而不是下一周，与国内周报口径一致
            case WEEK -> date.with(WeekFields.ISO.dayOfWeek(), 1);
            case MONTH -> date.withDayOfMonth(1);
        };
    }

    /** 桶标签：{@code yyyy-MM-dd} 形式的桶起始日 */
    public String bucketLabel(LocalDate date) {
        return bucketDate(date).toString();
    }

    /** 桶标签的显示名：DAY 用日期、WEEK 标「当周」、MONTH 用「yyyy-MM」 */
    public String displayLabel(LocalDate bucketStart) {
        return switch (this) {
            case DAY -> bucketStart.toString();
            case WEEK -> bucketStart + " 当周";
            case MONTH -> bucketStart.toString().substring(0, 7);
        };
    }

    /** 小写名称，用于响应里的 {@code granularity} 回显 */
    public String lowerName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
