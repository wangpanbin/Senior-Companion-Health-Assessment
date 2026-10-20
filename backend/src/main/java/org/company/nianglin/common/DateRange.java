package org.company.nianglin.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * 跨版本共享的统计时间区间（闭区间）—— V3.2 引入。
 *
 * <h3>为什么要抽到 common</h3>
 *
 * <p>V3.0 时期，同一个仓库里已经存在三套互不相同的区间约定：</p>
 * <ul>
 *   <li>M10 统计：默认 30 天、上限 366 天、闭区间；</li>
 *   <li>M6 服药日历：上限 31 天（限制的是<b>明细行数</b>，不是指标口径）；</li>
 *   <li>其它按需写死的接口：没有统一约定。</li>
 * </ul>
 *
 * <p>V3.2 起，<b>指标口径</b>类接口（概览 / 时间线 / 趋势 / 画像 / 驾驶舱）
 * 全部走这一套：默认 30 天、上限 366 天、闭区间。
 * 同一个指标在不同角色端出现不同的可查区间，是「口径分裂」最常见的表现形式，
 * 所以区间必须是共享值对象，而不是每个 Service 各写一遍收口逻辑。</p>
 *
 * <h3>纯值对象，不抛异常</h3>
 *
 * <p>本类<b>不依赖</b> {@code exception} 包（否则 {@code common} 与 {@code exception}
 * 会形成包循环），只负责「区间是什么」；
 * 「区间合不合法」由 {@code dto.DateRangeQuery#toRange()} 判定并抛业务异常。</p>
 *
 * @param start 区间开始日（含）
 * @param end   区间结束日（含）
 * @author 银龄伴诊团队
 * @since V3.2
 */
public record DateRange(LocalDate start, LocalDate end) {

    /** 默认区间长度（天） */
    public static final int DEFAULT_RANGE_DAYS = 30;

    /** 区间长度上限（天） */
    public static final int MAX_RANGE_DAYS = 366;

    /**
     * 区间天数（含首尾两天）。
     *
     * <p>用 {@code ChronoUnit.DAYS.between + 1} 而不是直接取差值：
     * 起点与终点同一天时区间应为 1 天，用差值算会得到 0，
     * 导致「只查今天」被误判成空区间。</p>
     */
    public long days() {
        return ChronoUnit.DAYS.between(start, end) + 1;
    }

    /** 区间起点时刻（当天 00:00:00） */
    public LocalDateTime startTime() {
        return start.atStartOfDay();
    }

    /**
     * 区间终点时刻（当天 23:59:59）。
     *
     * <p>用于 {@code BETWEEN ... AND ...} 的闭区间写法。</p>
     */
    public LocalDateTime endTime() {
        return end.atTime(23, 59, 59);
    }

    /**
     * 区间的<b>开</b>区间上界（结束日的次日 00:00:00）。
     *
     * <p>写 {@code < endExclusive} 而不是 {@code <= endTime}：
     * 后者在列精度变成 {@code DATETIME(3)} 之后会漏掉 23:59:59.500 这一秒。</p>
     */
    public LocalDateTime endExclusive() {
        return end.plusDays(1).atStartOfDay();
    }
}
