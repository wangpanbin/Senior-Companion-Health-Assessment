package org.company.nianglin.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 家属智能助手配置（V3.7 · M14 · T7）。
 *
 * <p>绑定 {@code application.yml} 的 {@code nianglin.assistant.*}。</p>
 *
 * <h3>为什么这些值要在启动期校验</h3>
 *
 * <p>照搬 {@link MatchingProperties} 的纪律：<b>错误的配置要在启动的十秒内暴露</b>。
 * 这里的每一个值配错都会让助手「安静地不好用」：超时太短会让长回答随机失败、
 * 限流阈值配成 0 会让助手对所有人不可用、FAQ 路径指错会让降级路径失效。
 * 这些都不会报错，只会让家属得到一个「莫名其妙」的体验。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.7
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "nianglin.assistant")
public class AssistantProperties {

    /** 助手总开关。关掉后端点直接返回 10002（不装配、不调用 Python）。 */
    private boolean enabled = true;

    /** Python 助手服务地址（只监听 127.0.0.1，不直接对浏览器开放）。 */
    private String serviceUrl = "http://127.0.0.1:8081";

    /** 连接超时（毫秒）。 */
    private int connectTimeoutMs = 3000;

    /** 读超时（毫秒）。LLM 长回答可能需要十几秒，所以给得比默认宽松。 */
    private int readTimeoutMs = 60000;

    /** 每个用户每分钟允许的提问次数。 */
    private int rateLimitPerMinute = 10;

    /**
     * 允许携带的历史<b>消息条数</b>上限。
     *
     * <p>⚠️ 单位是「条」不是「轮」：一轮 = user 一条 + assistant 一条 = 2 条。
     * 6 轮 = 12 条。写成「轮」而实际按「条」卡，会让用户问到第 5 轮就被拦成 400
     * ——<b>用得越久越坏</b>，而且只测 1–2 轮时完全看不出来。</p>
     */
    private int maxHistoryMessages = 12;

    /** 单条问题的长度上限。 */
    private int maxQuestionLength = 500;

    /** Python 不可用时是否走本地 FAQ 兜底。 */
    private boolean fallbackOnError = true;

    /**
     * 本地 FAQ 兜底用的知识库路径。
     *
     * <p>默认指向 {@code ai-service/knowledge/faq.json} —— 与 Python 服务读**同一个文件**，
     * 所以兜底时给家属的答案和大模型模式下的答案口径一致。
     * 相对路径按<b>后端进程的工作目录</b>解析（本项目为 {@code backend/}）。</p>
     */
    private String faqPath = "../ai-service/knowledge/faq.json";

    /**
     * 启动期校验：值不合理即启动失败。
     *
     * <p>理由同 {@link MatchingProperties#validate()}：
     * 这些值配错都不会报错，只会让助手安静地不好用。</p>
     */
    @PostConstruct
    void validate() {
        if (connectTimeoutMs < 100) {
            throw new IllegalStateException(
                    "nianglin.assistant.connect-timeout-ms 必须大于等于 100，当前为 " + connectTimeoutMs);
        }
        if (readTimeoutMs < 1000) {
            throw new IllegalStateException(
                    "nianglin.assistant.read-timeout-ms 必须大于等于 1000，当前为 " + readTimeoutMs);
        }
        if (rateLimitPerMinute < 1) {
            throw new IllegalStateException(
                    "nianglin.assistant.rate-limit-per-minute 必须大于等于 1，当前为 " + rateLimitPerMinute);
        }
        if (maxHistoryMessages < 0) {
            throw new IllegalStateException(
                    "nianglin.assistant.max-history-messages 不能为负，当前为 " + maxHistoryMessages);
        }
        if (maxQuestionLength < 1) {
            throw new IllegalStateException(
                    "nianglin.assistant.max-question-length 必须大于等于 1，当前为 " + maxQuestionLength);
        }
        if (enabled && (serviceUrl == null || serviceUrl.isBlank())) {
            throw new IllegalStateException("nianglin.assistant.service-url 不能为空");
        }
        log.info("智能助手配置已加载 | 服务地址={} | 限流={} 次/分钟 | 读超时={}ms",
                serviceUrl, rateLimitPerMinute, readTimeoutMs);
    }
}
