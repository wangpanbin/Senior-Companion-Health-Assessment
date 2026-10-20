package org.company.nianglin.dto;

import org.company.nianglin.common.DateRange;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨版本共享统计区间的单测（V3.2 · T-0 · FR-Range）。
 *
 * <p>区间判定散到每个接口里各写一遍，迟早在某一个接口上漏掉 ——
 * 而「区间反了」在 SQL 层不会报错，只会静默返回空数据。
 * 所以默认区间、上限、起止颠倒这三个判定必须被一张表钉住。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@DisplayName("共享统计区间：默认 30 天 / 上限 366 天 / 闭区间")
class DateRangeQueryTest {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /* ================================================================== */
    /* 默认值                                                              */
    /* ================================================================== */

    @Test
    @DisplayName("不传任何日期 → 最近 30 天（含今天）")
    void shouldDefaultToLastThirtyDays() {
        DateRange range = new DateRangeQuery().toRange();

        assertEquals(LocalDate.now(), range.end());
        assertEquals(30L, range.days());
    }

    @Test
    @DisplayName("只传开始日 → 从该日到今天")
    void shouldUseStartDateAndFallBackToToday() {
        LocalDate start = LocalDate.now().minusDays(6);
        DateRangeQuery query = new DateRangeQuery();
        query.setStartDate(start.format(DATE));

        DateRange range = query.toRange();

        assertEquals(start, range.start());
        assertEquals(LocalDate.now(), range.end());
        assertEquals(7L, range.days());
    }

    @Test
    @DisplayName("只传结束日 → 结束日往前推 30 天")
    void shouldFallBackToThirtyDaysBeforeEndDate() {
        LocalDate end = LocalDate.now().minusDays(10);
        DateRangeQuery query = new DateRangeQuery();
        query.setEndDate(end.format(DATE));

        DateRange range = query.toRange();

        assertEquals(end, range.end());
        assertEquals(30L, range.days());
        assertEquals(end.minusDays(29), range.start());
    }

    /* ================================================================== */
    /* 边界                                                                */
    /* ================================================================== */

    @Test
    @DisplayName("单日区间 → 天数为 1（不是 0）")
    void singleDayRangeShouldBeOneDay() {
        String today = LocalDate.now().format(DATE);
        DateRangeQuery query = new DateRangeQuery();
        query.setStartDate(today);
        query.setEndDate(today);

        assertEquals(1L, query.toRange().days());
    }

    @Test
    @DisplayName("恰好 366 天 → 放行")
    void exactlyMaxRangeShouldPass() {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(DateRange.MAX_RANGE_DAYS - 1L);
        DateRangeQuery query = new DateRangeQuery();
        query.setStartDate(start.format(DATE));
        query.setEndDate(end.format(DATE));

        assertEquals(DateRange.MAX_RANGE_DAYS, query.toRange().days());
    }

    @Test
    @DisplayName("367 天 → 9002")
    void tooWideRangeShouldBeRejected() {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(DateRange.MAX_RANGE_DAYS);
        DateRangeQuery query = new DateRangeQuery();
        query.setStartDate(start.format(DATE));
        query.setEndDate(end.format(DATE));

        BusinessException e = assertThrows(BusinessException.class, query::toRange);
        assertEquals(ResultCode.STAT_RANGE_INVALID.getCode(), e.getCode());
    }

    /* ================================================================== */
    /* 非法输入                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("起止颠倒 → 9002")
    void invertedRangeShouldBeRejected() {
        DateRangeQuery query = new DateRangeQuery();
        query.setStartDate(LocalDate.now().format(DATE));
        query.setEndDate(LocalDate.now().minusDays(5).format(DATE));

        BusinessException e = assertThrows(BusinessException.class, query::toRange);
        assertEquals(ResultCode.STAT_RANGE_INVALID.getCode(), e.getCode());
    }

    @Test
    @DisplayName("日期格式不合法 → 400")
    void badDateFormatShouldBeRejected() {
        DateRangeQuery query = new DateRangeQuery();
        query.setStartDate("2026/09/14");

        BusinessException e = assertThrows(BusinessException.class, query::toRange);
        assertEquals(ResultCode.PARAM_ERROR.getCode(), e.getCode());
    }

    @Test
    @DisplayName("空白字符串按未传处理（取默认区间）")
    void blankShouldBeTreatedAsAbsent() {
        DateRangeQuery query = new DateRangeQuery();
        query.setStartDate("   ");
        query.setEndDate("");

        assertEquals(30L, query.toRange().days());
    }

    /* ================================================================== */
    /* 折算语义                                                            */
    /* ================================================================== */

    @Test
    @DisplayName("endExclusive = 结束日次日零点（避免 DATETIME(3) 精度漏秒）")
    void endExclusiveShouldBeNextMidnight() {
        LocalDate start = LocalDate.of(2026, 9, 14);
        LocalDate end = LocalDate.of(2026, 9, 20);
        DateRangeQuery query = new DateRangeQuery();
        query.setStartDate(start.format(DATE));
        query.setEndDate(end.format(DATE));

        DateRange range = query.toRange();

        assertEquals(start.atStartOfDay(), range.startTime());
        assertEquals(end.atTime(23, 59, 59), range.endTime());
        assertEquals(end.plusDays(1).atStartOfDay(), range.endExclusive());
        assertTrue(range.endExclusive().isAfter(range.endTime()),
                "开区间上界必须严格晚于闭区间终点，否则会漏掉 23:59:59.500");
    }
}
