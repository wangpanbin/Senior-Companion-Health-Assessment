package org.company.nianglin.util;

import java.util.List;

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
 * @author 银龄伴诊团队
 * @since M7
 */
public final class SensitiveWordUtil {

    /**
     * 命中即拒的辱骂 / 违规引流词。
     *
     * <p>刻意保持短小：长词表会带来大量误伤（例如「滚」会命中「滚烫」），
     * 而一期并没有内容审核的人工兜底能力。
     * 这里只覆盖最明确、几乎不存在正常用法的几个词。</p>
     */
    private static final List<String> SENSITIVE_WORDS = List.of(
            // 人身攻击
            "垃圾", "滚蛋", "骗子", "骗人", "去死", "废物", "弱智", "傻逼", "脑残",
            // 违规引流（平台外交易 / 私下联系）
            "加微信", "加我微信", "私聊转账", "私下转账", "扫码付款"
    );

    private SensitiveWordUtil() {
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
}
