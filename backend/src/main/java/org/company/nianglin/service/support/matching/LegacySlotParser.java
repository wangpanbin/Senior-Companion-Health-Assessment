package org.company.nianglin.service.support.matching;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 自由文本「可服务时段」的解析器（V3.4 · FR-Struct 回退路径）。
 *
 * <h3>为什么需要它</h3>
 *
 * <p>V3.4 加了结构化的 {@code companion_available_slot} 表，但那张表对新数据才有内容 ——
 * 存量陪诊员的 {@code available_time} 还是自由文本（「周一至周五 08:00-18:00」）。
 * 如果只读结构化表，**所有存量陪诊员的时间维度都是 0**，匹配引擎等于少了一条腿。</p>
 *
 * <h3>解析范围（刻意保守）</h3>
 *
 * <p>只认种子与常见写法里的几种形态，解析不出来就返回空列表 ——
 * <b>宁可让这一维记 0 并标注，也不猜</b>：</p>
 * <ul>
 *   <li>星期：{@code 周一..周日}、{@code 星期一..星期日}、{@code 全周}、{@code 每天}、
 *       {@code 周末}（= 周六 + 周日）；</li>
 *   <li>区间：{@code 周一至周五}、{@code 周一-周五}、{@code 周一至周五、周六}；</li>
 *   <li>时刻：{@code 08:00-18:00}、{@code 08:00~18:00}、{@code 8:00-18:00}。</li>
 * </ul>
 *
 * <p>解析不出来 → 空列表 → 时间维度记 0 + 说明「未登记可服务时段」。
 * 这与「结构化表里也没有」是同一种结果，语义一致。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4
 */
public final class LegacySlotParser {

    /** 星期几的中文写法 → ISO 周内序号（1=周一 … 7=周日） */
    private static final String[] WEEKDAY_NAMES = {
            "一", "二", "三", "四", "五", "六", "日"
    };

    /** 时刻区间：08:00-18:00 / 8:00~18:00 */
    private static final Pattern TIME_RANGE = Pattern.compile("(\\d{1,2})[:：](\\d{2})\\s*[-~—至]\\s*(\\d{1,2})[:：](\\d{2})");

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    private LegacySlotParser() {
    }

    /**
     * 把自由文本解析成可服务时段。
     *
     * @param text 自由文本（可空）
     * @return 时段列表；解析不出任何内容时返回空列表（**不抛异常、不猜**）
     */
    public static List<MatchingCandidate.AvailableSlot> parse(String text) {
        List<MatchingCandidate.AvailableSlot> slots = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return slots;
        }

        Matcher timeMatcher = TIME_RANGE.matcher(text);
        if (!timeMatcher.find()) {
            return slots;
        }
        LocalTime start = toTime(timeMatcher.group(1), timeMatcher.group(2));
        LocalTime end = toTime(timeMatcher.group(3), timeMatcher.group(4));
        if (start == null || end == null) {
            return slots;
        }

        for (int weekday : weekdaysOf(text)) {
            slots.add(new MatchingCandidate.AvailableSlot(weekday, start, end));
        }
        return slots;
    }

    /**
     * 从文本里解析出周内第几天。
     *
     * @param text 自由文本
     * @return ISO 周内序号列表；解析不出时返回空列表
     */
    public static List<Integer> weekdaysOf(String text) {
        List<Integer> weekdays = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return weekdays;
        }

        // 「全周」「每天」→ 7 天
        if (text.contains("全周") || text.contains("每天") || text.contains("每日")) {
            for (int i = 1; i <= 7; i++) {
                weekdays.add(i);
            }
            return weekdays;
        }

        // 「周末」→ 周六 + 周日
        if (text.contains("周末")) {
            weekdays.add(6);
            weekdays.add(7);
        }

        // 「周一至周五」/「周一-周五」这类区间
        Matcher rangeMatcher = Pattern.compile("周([一二三四五六日])\\s*(?:至|到|-|~|—)\\s*周?([一二三四五六日])").matcher(text);
        while (rangeMatcher.find()) {
            int from = indexOf(rangeMatcher.group(1));
            int to = indexOf(rangeMatcher.group(2));
            if (from <= 0 || to <= 0) {
                continue;
            }
            for (int i = from; i <= to; i++) {
                weekdays.add(i);
            }
        }

        // 单个「周一」
        Matcher singleMatcher = Pattern.compile("周([一二三四五六日])").matcher(text);
        while (singleMatcher.find()) {
            int day = indexOf(singleMatcher.group(1));
            if (day > 0) {
                weekdays.add(day);
            }
        }

        return weekdays.stream().distinct().sorted().toList();
    }

    /** 「一」→ 1（周一）；「日」→ 7（周日）；找不到返回 0 */
    private static int indexOf(String chinese) {
        for (int i = 0; i < WEEKDAY_NAMES.length; i++) {
            if (WEEKDAY_NAMES[i].equals(chinese)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** "08" + "00" → 08:00；小时 > 23 或分钟 > 59 返回 {@code null} */
    private static LocalTime toTime(String hour, String minute) {
        try {
            int h = Integer.parseInt(hour);
            int m = Integer.parseInt(minute);
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return null;
            }
            return LocalTime.parse(String.format("%02d:%02d", h, m), HHMM);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
