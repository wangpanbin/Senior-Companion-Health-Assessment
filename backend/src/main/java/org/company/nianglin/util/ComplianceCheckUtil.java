package org.company.nianglin.util;

import java.util.List;

/**
 * 医疗建议类表述的拦截工具。
 *
 * <p><b>合规红线（plan.md「合规与隐私说明」）：平台不做诊断、不开药方。</b>
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
 * <h3>词表刻意"宁可误拒"</h3>
 *
 * <p>被拒时用户会看到具体命中的词（见 {@link #firstHit}），改写成本很低；
 * 而漏放一条「建议加量」的代价无法挽回。所以这里不追求词表的完备与精准，
 * 只覆盖最容易脱口而出的那批表述，并接受少量误拒。</p>
 *
 * <p>M7 的评价内容校验可直接复用本类，不要另起一份词表 ——
 * 两份词表一定会有一份先过期。</p>
 *
 * @author 银龄伴诊团队
 * @since M4
 */
public final class ComplianceCheckUtil {

    /**
     * 命中即判为「诊断 / 处方 / 用药建议」的词组。
     *
     * <p>分三类：诊断结论、开药动作、用药调整。刻意不含「药」「医院」「治疗」
     * 这类过于宽泛的词 —— 「已协助取药」是完全合法的过程记录。</p>
     */
    private static final List<String> MEDICAL_ADVICE_WORDS = List.of(
            // 诊断结论
            "诊断", "确诊", "疑似", "病情判断",
            // 开药动作
            "处方", "开具", "开药", "配药", "建议服用", "建议使用",
            // 用药调整
            "换药", "停药", "加量", "减量", "剂量", "服用方法", "用药方案"
    );

    private ComplianceCheckUtil() {
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
}
