package org.company.nianglin.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 评价 / 投诉内容的辱骂类敏感词拦截。
 *
 * <h3>与 {@link ComplianceCheckUtil} 的分工</h3>
 *
 * <p>两者拦的是完全不同的东西，不能合并：</p>
 * <ul>
 *   <li>{@link ComplianceCheckUtil} 拦的是<b>医疗建议</b>（诊断、处方、剂量…）。
 *       它服务于平台的合规红线，主要落在<b>陪诊员写的服务小结</b>上。</li>
 *   <li>本类拦的是<b>人身攻击与违规引流</b>。它服务于社区氛围，落在
 *       <b>家属写的评价</b>与<b>双方提交的投诉</b>上。</li>
 * </ul>
 *
 * <h3>本类刻意<b>不</b>套用医疗建议词表</h3>
 *
 * <p>家属写评价时出现「用药方案」「剂量」是完全自然的
 * （「陪诊员把我妈的用药方案抄了一份给我，很清楚」）。
 * 把这类描述当作违规，会让评价功能从「能用」变成「不敢写」——
 * 而评价是陪诊员唯一的正向激励来源。因此这里只做辱骂拦截。</p>
 *
 * <h3>词表与 M4 一样保持「宁可误拒」</h3>
 *
 * <p>被拒时提示会把命中的词回显给用户（复用了 {@code 6003} 的语义），
 * 改写成本很低；而漏放一条辱骂内容的代价是它永久留在陪诊员的公开评价里。</p>
 *
 * <h3>为什么词表是外部资源（V3.7 · ADR-0020 决策 3）</h3>
 *
 * <p>V3.7 的家属智能助手也拦辱骂 / 违规引流词（错误码 {@code 10003}）。
 * 若 Python 侧自抄一份，就又出现「两份词表一定会有一份先过期」的老问题 ——
 * 失效方式还特别隐蔽：Java 侧拦住的东西 Python 侧放行，两边行为分叉且无人报错。
 * 所以词表同样落到 {@code resources/compliance/sensitive-words.txt}，两侧读同一份。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 * @since V3.7 词表移出代码（ADR-0020 决策 3），行为不变
 */
public final class SensitiveWordUtil {

    /** 词表在 classpath 中的位置。Python 侧读的是仓库里的同一个文件。 */
    private static final String RESOURCE_PATH = "/compliance/sensitive-words.txt";

    /**
     * 必须存在的关键词 —— 少一个就说明读到的不是那份词表（读错路径 / 被裁剪）。
     *
     * <p>与 Python 侧 {@code paths.py} 的 {@code REQUIRED_SENSITIVE_WORDS} <b>必须一致</b>。</p>
     */
    private static final Set<String> REQUIRED_WORDS = Set.of("加微信", "扫码付款");

    /** 词表至少要有这么多词才算「读到了」，低于此值一律视为读空。 */
    private static final int MIN_WORDS = 8;

    /**
     * 命中即拒的辱骂 / 违规引流词。
     *
     * <p>刻意保持短小：长词表会带来大量误伤（例如「滚」会命中「滚烫」），
     * 而一期并没有内容审核的人工兜底能力。
     * 这里只覆盖最明确、几乎不存在正常用法的几个词。</p>
     *
     * <p>词条来自 {@code resources/compliance/sensitive-words.txt}，
     * 与 {@code ai-service/app/paths.py} 读的是<b>同一个文件</b>。</p>
     */
    private static final List<String> SENSITIVE_WORDS = loadWords();

    private SensitiveWordUtil() {
    }

    private static List<String> loadWords() {
        InputStream in = SensitiveWordUtil.class.getResourceAsStream(RESOURCE_PATH);
        if (in == null) {
            throw new IllegalStateException(
                    "敏感词表资源缺失：" + RESOURCE_PATH);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> words = reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
            if (words.size() < MIN_WORDS) {
                throw new IllegalStateException(
                        "敏感词表词数异常：" + words.size() + " < " + MIN_WORDS + "，来源 " + RESOURCE_PATH);
            }
            if (!words.containsAll(REQUIRED_WORDS)) {
                throw new IllegalStateException(
                        "敏感词表缺少关键词（可能读错文件）：" + RESOURCE_PATH);
            }
            return words;
        } catch (IOException e) {
            throw new IllegalStateException("敏感词表读取失败：" + RESOURCE_PATH, e);
        }
    }

    /**
     * 返回第一个命中的词，没有命中返回 {@code null}。
     *
     * <p>与 {@code ComplianceCheckUtil.firstHit} 保持同样的契约，
     * 便于调用方用同一段代码处理两种校验。</p>
     */
    public static String firstHit(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (String word : SENSITIVE_WORDS) {
            if (text.contains(word)) {
                return word;
            }
        }
        return null;
    }

    /** 是否含有辱骂 / 违规引流词 */
    public static boolean containsSensitiveWord(String text) {
        return firstHit(text) != null;
    }

    /**
     * 词表的只读快照（用于自检与测试断言）。
     *
     * <p>与 {@code ComplianceCheckUtil#words()} 同理：让「词表到底有哪些词」可被机器校验。</p>
     */
    public static List<String> words() {
        return SENSITIVE_WORDS;
    }
}
