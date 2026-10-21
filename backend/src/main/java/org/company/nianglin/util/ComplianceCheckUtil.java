package org.company.nianglin.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * 医疗建议类表述的拦截工具。
 *
 * <p><b>合规红线（docs/plan/plan.md「合规与隐私说明」：平台不做诊断、不开药方。）</b>
 * 这条红线在代码层需要一个落点 —— 否则陪诊员在「服务小结」里写下
 * 「医生建议服用阿司匹林，每天一次」，平台就成了一则医疗建议的发布渠道，
 * 而这个责任是学生项目承担不起的。</p>
 *
 * <h3>为什么是「命中即拒」而不是「打码后放行」</h3>
 *
 * <p>打码会把「建议服用阿司匹林」变成「建议**阿司匹林」，读者仍能猜出原意，
 * 拦不住风险却增加了维护成本。直接拒收，让撰写者改写成
 * 「医生已协助完成取药流程」这类过程性描述 —— 既保留了陪诊记录的价值，
 * 又完全不含医学判断。</p>
 *
 * <h3>为什么词表是外部资源而不是硬编码常量</h3>
 *
 * <p><b>V3.7 智能助手（ADR-0020 决策 3）引入了第二个消费方</b>：
 * Python 侧的 {@code ai-service/app/guard.py} 也需要同一份词表。
 * 而本类原来的 javadoc 写过一句话 ——「不要另起一份词表 —— 两份词表一定会有一份先过期」——
 * 既然已经有第二个消费方，就不能只靠一句纪律，必须让两边<b>结构上</b>读同一份文件。</p>
 *
 * <p>因此词表移出代码，落到 {@code resources/compliance/medical-advice-words.txt}：
 * Java 从 classpath 读，Python 从可配路径读同一份文件。
 * 词表<b>17 个词一字未改</b>，本类的签名与行为完全不变。</p>
 *
 * <h3>读不到就启动失败</h3>
 *
 * <p>词表读空 = 护栏静默失效 = 所有医疗表述放行 = 平台变成医疗建议发布渠道。
 * 这种故障<b>不会报错</b>，只会让一切「看起来正常」——
 * 所以在静态初始化阶段就失败（照搬 {@code MatchingProperties#validate} 的纪律）。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 * @since V3.7 词表移出代码（ADR-0020 决策 3），行为不变
 */
public final class ComplianceCheckUtil {

    /** 合规词表在 classpath 中的位置。Python 侧读的是仓库里的同一个文件。 */
    private static final String RESOURCE_PATH = "/compliance/medical-advice-words.txt";

    /** 固定拒答模板在 classpath 中的位置。Python 侧读的是同一个文件。 */
    private static final String REFUSAL_TEMPLATE_PATH = "/compliance/refusal-template.txt";

    /**
     * 必须存在的关键词 —— 少一个就说明读到的不是那份词表（读错路径 / 被裁剪）。
     *
     * <p>与 Python 侧 {@code paths.py} 的 {@code REQUIRED_COMPLIANCE_WORDS} <b>必须一致</b>：
     * 两边口径不同就会出现「Java 放行、Python 拒启动」的分叉。</p>
     */
    private static final Set<String> REQUIRED_WORDS = Set.of("建议服用", "剂量");

    /** 词表至少要有这么多词才算「读到了」，低于此值一律视为读空。 */
    private static final int MIN_WORDS = 10;

    /**
     * 命中即判为「诊断 / 处方 / 用药建议」的词组。
     *
     * <p>分三类：诊断结论、开药动作、用药调整。刻意不含「药」「医院」「治疗」
     * 这类过于宽泛的词 —— 「已协助取药」是完全合法的过程记录。</p>
     *
     * <p>词条来自 {@code resources/compliance/medical-advice-words.txt}，
     * 与 {@code ai-service/app/paths.py} 读的是<b>同一个文件</b>。</p>
     */
    private static final List<String> MEDICAL_ADVICE_WORDS = loadWords(RESOURCE_PATH, REQUIRED_WORDS);

    /** 固定拒答模板 —— 医疗类提问被拦下时返回的这句话。 */
    private static final String REFUSAL_TEMPLATE = loadRefusalTemplate();

    private ComplianceCheckUtil() {
    }

    /**
     * 从 classpath 读取词表。
     *
     * <p>失败一律抛 {@link IllegalStateException} —— 静态初始化失败会让应用启动失败，
     * 这正是我们要的：护栏失效不能等到「医疗建议被放行」之后才发现。</p>
     */
    private static List<String> loadWords(String path, Set<String> required) {
        InputStream in = ComplianceCheckUtil.class.getResourceAsStream(path);
        if (in == null) {
            throw new IllegalStateException(
                    "合规词表资源缺失（护栏会静默失效）：" + path);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> words = reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
            if (words.size() < MIN_WORDS) {
                throw new IllegalStateException(
                        "合规词表词数异常（护栏会静默失效）：" + words.size() + " < " + MIN_WORDS
                                + "，来源 " + path);
            }
            if (!words.containsAll(required)) {
                throw new IllegalStateException(
                        "合规词表缺少关键词（可能读错文件）：" + path);
            }
            return words;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "合规词表读取失败（护栏会静默失效）：" + path, e);
        }
    }

    private static String loadRefusalTemplate() {
        InputStream in = ComplianceCheckUtil.class.getResourceAsStream(REFUSAL_TEMPLATE_PATH);
        if (in == null) {
            throw new IllegalStateException("拒答模板资源缺失：" + REFUSAL_TEMPLATE_PATH);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String text = reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .reduce("", (left, right) -> left.isEmpty() ? right : left + " " + right);
            if (text.isEmpty()) {
                throw new IllegalStateException("拒答模板为空：" + REFUSAL_TEMPLATE_PATH);
            }
            return text;
        } catch (IOException e) {
            throw new IllegalStateException("拒答模板读取失败：" + REFUSAL_TEMPLATE_PATH, e);
        }
    }

    /**
     * 返回第一个命中的词，没有命中返回 {@code null}。
     *
     * <p>返回具体词而不是布尔值，是为了让错误提示能告诉用户「哪里不对」——
     * 只说「包含敏感词」会让用户反复试错，最后干脆不写小结了。</p>
     */
    public static String firstHit(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (String word : MEDICAL_ADVICE_WORDS) {
            if (text.contains(word)) {
                return word;
            }
        }
        return null;
    }

    /** 是否含有诊断 / 处方 / 用药建议类表述 */
    public static boolean containsMedicalAdvice(String text) {
        return firstHit(text) != null;
    }

    /**
     * 合规词表的只读快照（用于自检与测试断言）。
     *
     * <p>暴露出来是为了让「词表到底有哪些词」可以被机器校验 ——
     * Java 单测与 Python 单测各自冻结一份快照，两边不一致就是词表漂移。</p>
     */
    public static List<String> words() {
        return MEDICAL_ADVICE_WORDS;
    }

    /**
     * 固定拒答模板（医疗类提问被拦下时返回的这句话）。
     *
     * <p>与 Python 侧 {@code app/guard.py} 的 {@code REFUSAL_TEMPLATE} <b>读同一个文件</b>，
     * 所以两侧给用户看的拒答话术不会各说各话。</p>
     */
    public static String refusalTemplate() {
        return REFUSAL_TEMPLATE;
    }
}
