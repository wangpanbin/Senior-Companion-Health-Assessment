package org.company.nianglin.service.support.metrics;

import java.math.BigDecimal;

/**
 * 指标值的解析与差值计算（V3.6 · T3 · FR-Effect）。
 *
 * <h3>为什么需要它</h3>
 *
 * <p>计算器返回的是<b>已经渲染好的字符串</b>（{@code "96.40%"} / {@code "24"} / {@code "4.50"}），
 * 因为同一个计算器要同时喂饱「驾驶舱」与「效果对比」两个出口。
 * 但差值必须能算 —— 于是这里把渲染结果再解析回数值。</p>
 *
 * <h3>差值的单位</h3>
 *
 * <p>百分比指标的差值是<b>百分点</b>（{@code 96.40% − 92.10% = +4.30%}），
 * 不是「相对提升 4.67%」。相对提升在运营看板上更常见，但也更容易被误读成
 * 「履约率提升了 4.3%」—— 而实际是「提升了 4.3 个百分点」。
 * 这里选百分点，因为它是可加的：两期相减就是差。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.6
 */
public final class MetricValues {

    /** 差值无法计算时的占位（两个值的格式不同、或有值无法解析） */
    public static final String NO_DELTA = "—";

    /**
     * 一个已渲染指标值的结构化形态。
     *
     * @param number  数值（百分比的 {@code %} 已剥离）
     * @param percent 是否百分比
     */
    public record Value(BigDecimal number, boolean percent) {
    }

    private MetricValues() {
    }

    /**
     * 解析渲染后的指标值。
     *
     * @param formatted 形如 {@code "96.40%"} / {@code "24"} / {@code "4.50"} 的字符串
     * @return 解析结果；<b>解析不了返回 {@code null}</b>（不抛异常）
     */
    public static Value parse(String formatted) {
        if (formatted == null || formatted.isBlank()) {
            return null;
        }
        String text = formatted.trim();
        boolean percent = text.endsWith("%");
        if (percent) {
            text = text.substring(0, text.length() - 1).trim();
        }
        try {
            return new Value(new BigDecimal(text), percent);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 计算两期差值。
     *
     * @param currentFormatted  后一期（区间内）的渲染值
     * @param previousFormatted 前一期（对比期）的渲染值
     * @return 带正负号的差值；两侧格式不同（如 {@code %} vs 计数）或有值解析不了时返回
     *         {@value #NO_DELTA}
     */
    public static String delta(String currentFormatted, String previousFormatted) {
        Value current = parse(currentFormatted);
        Value previous = parse(previousFormatted);
        if (current == null || previous == null) {
            return NO_DELTA;
        }
        if (current.percent() != previous.percent()) {
            // 一边是百分比、一边是计数，相减没有意义 —— 不硬凑一个数出来
            return NO_DELTA;
        }
        BigDecimal diff = current.number().subtract(previous.number());
        String sign = diff.signum() >= 0 ? "+" : "-";
        String body = diff.abs().setScale(current.number().scale(), java.math.RoundingMode.HALF_UP)
                .toPlainString();
        return sign + body + (current.percent() ? "%" : "");
    }
}
