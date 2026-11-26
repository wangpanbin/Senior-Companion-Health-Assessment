package org.company.nianglin.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 自由文本 → 区域码解析（V3.4 结构化匹配的<b>运行期</b>写入点）。
 *
 * <h3>为什么需要它（补的是一条真实的失效链）</h3>
 *
 * <p>V7 迁移用一条条 {@code UPDATE ... WHERE address LIKE '%美兰区%'} 把<b>当时库里已有</b>的
 * 订单与陪诊员回填了区域码。但<b>回填是一次性的</b> —— 此后通过接口新创建的订单、
 * 新提交的陪诊员资质，{@code service_region_code} 恒为 {@code null}，只能靠
 * {@link org.company.nianglin.service.support.matching.RegionMatchRule} 的
 * 「结构化列缺失 → 回退自由文本包含式」兜住。</p>
 *
 * <p>回退本身没错（它是为老数据设计的），但它的判定是
 * 「陪诊员的 {@code service_area} 整串出现在订单地址里」——
 * {@code 海口市美兰区} 出现在 {@code 海南省海口市美兰区人民大道43号} 里能命中，
 * 而陪诊员填的是 {@code 美兰区}、订单地址写的是 {@code 海口美兰区…} 时就不命中。
 * 更要紧的是：{@code RegionMatchRule} 的<b>同市不同区 = 0.5</b> 这条口径
 * 在回退路径上<b>根本用不上</b>（自由文本只有「命中 1.0 / 不命中 0」两档）。
 * 也就是说，新订单的区域维度实际是「0 或 1 的赌博」，而不是 V3.4 设计的「1 / 0.5 / 0」。</p>
 *
 * <h3>单一真源：读的是迁移用的同一份 JSON</h3>
 *
 * <p>映射表在 {@code backend/sql/tools/region_code_mapping.json} ——
 * 它原本只是 {@code gen_region_codes.py} 生成 V7 backfill 的输入。
 * 这里<b>不另抄一份 Java 词表</b>，而是由 {@code pom.xml} 把同一个文件复制进 classpath
 * （见 {@code pom.xml} 的 {@code sql/tools → matching/} 资源段）：
 * 抄一份的必然结果是「谁先过期」——{@code ComplianceCheckUtil} 的类注释里
 * 已经为词表写过一遍这条纪律，这里照办。</p>
 *
 * <h3>匹配语义必须与 V7 backfill 逐字同构</h3>
 *
 * <p>V7 的 backfill 是一串顺序执行的
 * {@code UPDATE ... WHERE service_region_code IS NULL AND <文本列> LIKE '%<关键词>%'}。
 * {@code IS NULL} 这个条件意味着<b>先执行的赢</b>：一条地址若同时含两个区县名，
 * 落在 <b>映射文件里靠前</b> 的那条。本类因此也按<b>文件顺序</b>返回第一个命中的区域码，
 * 而不是「最长关键词」或「最后一个」——否则回填出来的老数据与新写入的数据
 * 会在同一份地址上得出不同区域码，同一张表里出现两种口径。</p>
 *
 * <h3>读不到就启动失败</h3>
 *
 * <p>与 {@link ComplianceCheckUtil} / {@link SensitiveWordUtil} 同源：
 * 资源缺失、JSON 解析失败、区域码重复、格式不合 {@code <CITY>_<DISTRICT>}，
 * 一律抛 {@link IllegalStateException}。静默降级成「全部返回 null」
 * 会让区域维度悄悄退回「0 或 1 的赌博」，而且看起来一切正常 —— 那是最难定位的一类故障。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.4（运行期写入点：2026-11-25 补）
 */
public final class RegionCodeResolver {

    /**
     * 区域码映射表在 classpath 中的位置（由 pom.xml 从 backend/sql/tools/ 复制而来）。
     *
     * <p>⚠️ {@link #REQUIRED_CODES} 里那 8 个码是**映表表本身的已知内容列表**，不是映射
     * 规则的第二份副本：它不参与任何匹配（匹配只读 JSON 里的 keywords），
     * 只用来回答「读到的到底是不是那份表」——与 {@link org.company.nianglin.util.ComplianceCheckUtil}
     * 的 {@code REQUIRED_WORDS} 同一个用途。真正不能抄的是「关键词 → 区域码」的对应关系，
     * 那部分只存在于 JSON 里（ADR-0016 §「回填只覆盖存量」）。
     */
    private static final String RESOURCE_PATH = "/matching/region_code_mapping.json";

    /** 区域码清单至少要有这么多条才算「读到了」，低于此值一律视为读空 */
    private static final int MIN_REGIONS = 8;

    /** 必须有这几条 —— 少一个就说明读到的不是那份映射表 */
    private static final Set<String> REQUIRED_CODES = Set.of(
            "HAIKOU_MEILAN", "HAIKOU_LONGHUA", "HAIKOU_XIUYING", "HAIKOU_QONGSHAN",
            "SANYA_JIYANG", "SANYA_TIANYA", "DANZHOU_NADA", "QIONGHAI_JIJI");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 映射规则，<b>按文件顺序</b> —— 顺序即优先级，见类注释「先执行的赢」 */
    private static final List<RegionRule> RULES = loadRules();

    private RegionCodeResolver() {
    }

    /**
     * 把自由文本（订单地址 / 陪诊员服务区域）解析成区域码。
     *
     * @param text 待解析的自由文本；{@code null} 或空白返回 {@code null}
     * @return 区域码（{@code <CITY>_<DISTRICT>}）；没有命中任何区县名时返回 {@code null}
     */
    public static String resolve(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim();
        for (RegionRule rule : RULES) {
            for (String keyword : rule.keywords()) {
                if (normalized.contains(keyword)) {
                    return rule.code();
                }
            }
        }
        return null;
    }

    /** 映射表里的全部区域码（按文件顺序）；供校验与诊断使用 */
    public static List<String> allCodes() {
        return RULES.stream().map(RegionRule::code).toList();
    }

    /* ================================================================== */
    /* 内部：加载                                                          */
    /* ================================================================== */

    /**
     * 一条映射规则。
     *
     * @param code     区域码，形如 {@code HAIKOU_MEILAN}
     * @param keywords 命中关键词（迁移里的 {@code LIKE '%kw%'} 用的就是它）
     */
    private record RegionRule(String code, List<String> keywords) {
    }

    private static List<RegionRule> loadRules() {
        InputStream in = RegionCodeResolver.class.getResourceAsStream(RESOURCE_PATH);
        if (in == null) {
            throw new IllegalStateException(
                    "区域码映射表资源缺失（区域维度会静默退化）：" + RESOURCE_PATH
                            + "；检查 pom.xml 的 sql/tools → matching/ 资源段是否被删");
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            Map<String, Object> root = MAPPER.readValue(reader,
                    new TypeReference<Map<String, Object>>() {
                    });
            Object rawRegions = root.get("regions");
            if (!(rawRegions instanceof List<?> regions)) {
                throw new IllegalStateException("映射表缺少 regions 数组：" + RESOURCE_PATH);
            }
            List<RegionRule> rules = new java.util.ArrayList<>(regions.size());
            Set<String> seen = new java.util.HashSet<>();
            for (Object raw : regions) {
                rules.add(toRule(raw, seen));
            }
            if (rules.size() < MIN_REGIONS) {
                throw new IllegalStateException(
                        "区域码条数异常（映射表可能被裁剪）：" + rules.size() + " < " + MIN_REGIONS);
            }
            Set<String> codes = rules.stream().map(RegionRule::code)
                    .collect(java.util.stream.Collectors.toSet());
            if (!codes.containsAll(REQUIRED_CODES)) {
                throw new IllegalStateException(
                        "区域码映射缺少已知条目（可能读错文件）：" + RESOURCE_PATH);
            }
            return List.copyOf(rules);
        } catch (IOException e) {
            throw new IllegalStateException("区域码映射表读取失败：" + RESOURCE_PATH, e);
        }
    }

    private static RegionRule toRule(Object raw, Set<String> seenCodes) {
        if (!(raw instanceof Map<?, ?> entry)) {
            throw new IllegalStateException("regions 里出现非对象元素：" + raw);
        }
        String code = text(entry.get("code"));
        if (code == null || !code.contains("_")) {
            throw new IllegalStateException("区域码格式不对（应为 <CITY>_<DISTRICT>）：" + raw);
        }
        if (!seenCodes.add(code)) {
            throw new IllegalStateException("区域码重复：" + code);
        }
        if (!(entry.get("keywords") instanceof List<?> rawKeywords) || rawKeywords.isEmpty()) {
            throw new IllegalStateException("区域码 " + code + " 缺少 keywords");
        }
        List<String> keywords = new java.util.ArrayList<>(rawKeywords.size());
        for (Object keyword : rawKeywords) {
            String value = text(keyword);
            // 空关键词会让 contains("") 恒真 —— 直接让它在启动期暴露
            if (value == null) {
                throw new IllegalStateException("区域码 " + code + " 含空关键词");
            }
            keywords.add(value);
        }
        return new RegionRule(code, List.copyOf(keywords));
    }

    /** JSON 里的值转字符串；不是非空字符串一律视为缺失 */
    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String trimmed = String.valueOf(value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 把区域码的城市前缀取出来（{@code HAIKOU_MEILAN → HAIKOU}）。
     *
     * <p>⚠️ 唯一实现是 {@link org.company.nianglin.service.support.matching.RegionMatchRule#cityOf} ——
     * 这里以前复制了第二份（注释理由是「让写入侧不必依赖匹配规则包」），
     * 但写入侧（{@code OrderServiceImpl} / {@code CompanionServiceImpl}）只调 {@link #resolve}，
     * 根本用不到它，而复制出来的两份靠一条「两份要相等」的测试维持一致 ——
     * 那份测试本身就是这份重复唯一的存在理由。已删。</p>
     */
    public static String cityOf(String regionCode) {
        if (regionCode == null) {
            return null;
        }
        return org.company.nianglin.service.support.matching.RegionMatchRule
                .cityOf(regionCode.trim().toUpperCase(Locale.ROOT));
    }
}
