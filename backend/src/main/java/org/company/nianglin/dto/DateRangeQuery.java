package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.company.nianglin.common.DateRange;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.exception.BusinessException;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 跨版本共享的统计区间入参（V3.2 · T-0）。
 *
 * <h3>默认与上限</h3>
 *
 * <ul>
 *   <li>不传 → 默认最近 <b>30 天</b>（含今天）；</li>
 *   <li>只传开始日 → 从开始日到今天；</li>
 *   <li>只传结束日 → 结束日往前推 30 天；</li>
 *   <li>区间长度上限 <b>366 天</b>；</li>
 *   <li>区间为<b>闭区间</b>。</li>
 * </ul>
 *
 * <h3>为什么「解析 + 校验」放在 DTO 而不是 Value Object</h3>
 *
 * <p>{@link DateRange} 是纯值对象，不依赖 {@code exception} 包；
 * 把「格式不对 / 区间反了 / 超上限」这三个判定收在这里，
 * V3.2 / V3.3 / V3.5 就不会再各写一遍 —— 少写一遍就少漏一遍。</p>
 *
 * <h3>为什么不用链式 setter</h3>
 *
 * <p>本类会被 {@code StatisticsQuery} 继承。链式 setter 会让继承来的
 * {@code setStartDate} 返回父类型而不是子类，既有调用点的方法签名会变 ——
 * 那是「为了写法好看」付出「破坏继承契约」的代价。区间入参是 GET 绑定的，
 * 根本不需要链式。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.2
 */
@Data
@Schema(description = "统计区间入参（两个字段都可空，缺省取最近 30 天）")
public class DateRangeQuery {

    @Schema(description = "区间开始日期（含），yyyy-MM-dd；不传则按默认区间推算",
            example = "2026-09-14")
    private String startDate;

    @Schema(description = "区间结束日期（含），yyyy-MM-dd；不传则取今天", example = "2026-09-20")
    private String endDate;

    /**
     * 解析成校验过的区间值对象。
     *
     * <p>三个容易错的地方都在这里收口：默认最近 30 天、起止颠倒、超过 366 天。
     * 散到每个接口里各写一遍，迟早在某一个接口上漏掉 ——
     * 而「区间反了」在 SQL 层不会报错，只会静默返回空数据。</p>
     *
     * @return 区间值对象，永不为 {@code null}
     * @throws BusinessException 格式不合法（400）/ 起止颠倒或超上限（9002）
     */
    public DateRange toRange() {
        LocalDate today = LocalDate.now();
        LocalDate end = parseDate(this.endDate, "结束日期");
        LocalDate start = parseDate(this.startDate, "开始日期");
        if (end == null) {
            end = today;
        }
        if (start == null) {
            start = end.minusDays(DateRange.DEFAULT_RANGE_DAYS - 1L);
        }
        if (start.isAfter(end)) {
            throw new BusinessException(ResultCode.STAT_RANGE_INVALID, "开始日期不能晚于结束日期");
        }
        DateRange range = new DateRange(start, end);
        if (range.days() > DateRange.MAX_RANGE_DAYS) {
            throw new BusinessException(ResultCode.STAT_RANGE_INVALID,
                    "统计区间不能超过 " + DateRange.MAX_RANGE_DAYS + " 天，请缩小范围");
        }
        return range;
    }

    /**
     * 解析日期字面量。
     *
     * @param value  原值，可为 {@code null} 或空白
     * @param field  字段中文名，用于错误提示
     * @return 解析结果；空值返回 {@code null}
     */
    private static LocalDate parseDate(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(ResultCode.PARAM_ERROR, field + " 格式应为 yyyy-MM-dd");
        }
    }
}
