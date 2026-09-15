package org.company.nianglin.util;

/**
 * 敏感信息脱敏工具。
 *
 * <p><b>合规红线（plan.md / 计划书「合规与隐私说明」）：</b></p>
 * <ol>
 *   <li>手机号必须脱敏展示（{@code 138****8888}）</li>
 *   <li>身份证号禁止出现在日志中；接口返回时必须脱敏</li>
 *   <li>姓名按场景脱敏（{@code 张*丰} 或 {@code 张**}）</li>
 * </ol>
 *
 * <p>⚠️ 本工具同时被日志与 VO 装配使用。写日志前必须先脱敏再拼接，禁止先拼字符串再脱敏（容易漏字段）。</p>
 *
 * <p><b>实现说明</b>：这里刻意**不依赖第三方字符串工具的 hide/replace 语义**。
 * 早期版本使用 {@code StrUtil.hide(str, start, end)}，当 start 与 end 相等时该工具会原样返回，
 * 导致 2 个字符的姓名（如「李四」）完全没有被脱敏 —— 这类静默失败在隐私场景下是不可接受的。
 * 因此改为显式的逐字符遮蔽，行为完全可控，并由 MaskUtilTest 逐条锁定。</p>
 *
 * @author 银龄伴诊团队
 */
public final class MaskUtil {

    private static final char MASK_CHAR = '*';

    private MaskUtil() {
    }

    /* ------------------------------------------------------------------ */
    /* 核心实现                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 保留前 prefix 位与后 suffix 位，中间全部替换为 {@code *}。
     *
     * <p>边界规则（关键，避免出现「静默不脱敏」）：</p>
     * <ul>
     *   <li>值为空 → 原样返回</li>
     *   <li>长度仅 1 位 → 原样返回（遮掉就没法识别了，无隐私价值）</li>
     *   <li>保留位数已覆盖全部字符 → 退化为「只保留首位，其余全遮」，保证至少遮蔽 1 位</li>
     * </ul>
     */
    private static String mask(String value, int prefix, int suffix) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        int len = v.length();
        if (len <= 1) {
            return v;
        }

        int keepPrefix = Math.max(0, prefix);
        int keepSuffix = Math.max(0, suffix);

        if (keepPrefix + keepSuffix >= len) {
            // 至少遮蔽 1 位
            keepPrefix = Math.min(keepPrefix, len - 1);
            keepSuffix = 0;
        }

        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            boolean keep = i < keepPrefix || i >= len - keepSuffix;
            sb.append(keep ? v.charAt(i) : MASK_CHAR);
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /* 常用场景                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 手机号脱敏：{@code 13812348888 -> 138****8888}
     */
    public static String phone(String phone) {
        return mask(phone, 3, 4);
    }

    /**
     * 身份证号脱敏：{@code 110101199003074567 -> 110101********4567}
     */
    public static String idCard(String idCard) {
        return mask(idCard, 6, 4);
    }

    /**
     * 姓名脱敏（保留姓氏与末字）：{@code 张三丰 -> 张*丰}；{@code 李四 -> 李*}
     */
    public static String name(String name) {
        return mask(name, 1, 1);
    }

    /**
     * 姓名全隐（只留姓氏）：{@code 张三丰 -> 张**}
     */
    public static String nameAll(String name) {
        return mask(name, 1, 0);
    }

    /**
     * 银行卡 / 账号脱敏：{@code 6222020200112233445 -> 6222***********3445}
     */
    public static String bankCard(String cardNo) {
        return mask(cardNo, 4, 4);
    }

    /**
     * 通用脱敏：保留前 prefix 位与后 suffix 位。
     */
    public static String generic(String value, int prefix, int suffix) {
        return mask(value, prefix, suffix);
    }

    /**
     * 地址脱敏：只保留到门牌号之前，门牌号用 {@code ***} 替代。
     *
     * <p>例：{@code 海南省海口市美兰区人民大道12号3栋501 -> 海南省海口市美兰区人民大道***}</p>
     */
    public static String address(String address) {
        if (address == null) {
            return null;
        }
        String v = address.trim();
        if (v.isEmpty()) {
            return v;
        }
        // 遇到第一个数字即认为是门牌号起点
        for (int i = 0; i < v.length(); i++) {
            if (Character.isDigit(v.charAt(i))) {
                return v.substring(0, i) + "***";
            }
        }
        return v;
    }
}
