package org.company.nianglin.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 医疗建议类表述的拦截工具。
 *
 * <p><b>合规红线（plan.md「合规与隐私说明」：平台不做诊断、不开药方。）</b>
 * 这条红线在代码层需要一个落点 —— 否则陪诊员在「服务小结」里写下
 * 「医生建议服用阿司匹林，每天一次」，平台就成了一则医疗建议的发布渠道，
 * 而这个责任是学生项目承担不起的。</p>
 *
 * <h3>为什么是「命中即拒」而不是「打码后放行」</h3>
 *
 * <p>打码会把「建议服用阿司匹林」变成「建议**阿司匹林」，读者仍能猜出原意，
 * 拦不住风险却增加了维护成本。直接拒收，让撰写者改写成
 * 「医生开具了处方，已协助取药」这类过程性描述 —— 既保留了陪诊记录的价值，
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
 * 词表**17 个词一字未改**，本类的签名与行为完全不变。</p>
 *
 * <p><b>读不到就启动失败</b>：词表读空 = 护栏静默失效 = 最危险的一类故障。
 * 它不会报错，只会让所有医疗表述放行，而且看起来一切正常 ——
 * 所以在静态初始化阶段就失败（照搬 {@code MatchingProperties#validate} 的纪律）。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 * @since V3.7 词表移出代码（ADR-0020 决策 3），行为不变
 */
public final class ComplianceCheckUtil {

    /** 合规词表在 classpath 中的位置。Python 侧读的是仓库里的同一个文件。 */
    private static final String RESOURCE_PATH = "/compliance/medical-advice-words.txt";

    /**
     * 命中即判为「诊断 / 处方 / 用药建议」的词组。
     *
     * <p>分三类：诊断结论、开药动作、用药调整。刻意不含「药」「医院」「治疗」
     * 这类过于宽泛的词 —— 「已协助取药」是完全合法的过程记录。</p>
     *
     * <p>词条来自 {@code resources/compliance/medical-advice-words.txt}，
     * 与 {@code ai-service/app/paths.py} 读的是**同一个文件**。</p>
     */
    private static final List<String> MEDICAL_ADVICE_WORDS = loadWords();

    private ComplianceCheckUtil() {
    }

    /**
     * 从 classpath 读取合规词表。
     *
     * <p>失败一律抛 {@link IllegalStateException} —— 静态初始化失败会让应用启动失败，
     * 这正是我们要的：护栏失效不能等到「医疗建议被放行」之后才发现。</p>
     */
    private static List<String> loadWords() {
        InputStream in = ComplianceCheckUtil.class.getResourceAsStream(RESOURCE_PATH);
        if (in == null) {
            throw new IllegalStateException(
                    "合规词表资源缺失（护栏会静默失效）：" + RESOURCE_PATH);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> words = reader.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
            if (words.size() < 10) {
                throw new IllegalStateException(
                        "合规词表词数异常（护栏会静默失效）：" + words.size() + " < 10，来源 " + RESOURCE_PATH);
            }
            return words;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "合规词表读取失败（护栏会静默失效）：" + RESOURCE_PATH, e);
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
}
