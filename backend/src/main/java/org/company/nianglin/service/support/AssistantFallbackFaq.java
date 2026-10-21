package org.company.nianglin.service.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.config.AssistantProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 助手问答的**本地兜底**知识库（V3.7 · M14）。
 *
 * <h3>为什么后端要自己读一份 FAQ</h3>
 *
 * <p>Python 助手服务可能没起、可能崩了、可能网络不通。这时候如果直接报错，
 * 家属得到的是一句「智能助手暂时不可用」——<b>而这些问题其实都有标准答案</b>。
 * 所以后端自己读**同一份** {@code ai-service/knowledge/faq.json} 做兜底。</p>
 *
 * <h3>为什么读的是同一个文件而不是复制一份</h3>
 *
 * <p>复制 = 两份内容必然漂移（``ComplianceCheckUtil`` 的 javadoc 写过这个教训）。
 * 读同一个文件 = 大模型模式与兜底模式给家属的答案口径一致。</p>
 *
 * <h3>兜底的检索刻意比 Python 侧简单</h3>
 *
 * <p>只做「关键词命中个数」的最大值比较，不做 2-gram 重叠。
 * 兜底是<b>降级</b>，宁可用简单算法，也不要引入第二套需要同步维护的检索逻辑。
 * 真正需要精确检索时，Python 服务在跑。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.7
 */
@Slf4j
@Component
public class AssistantFallbackFaq {

    private final AssistantProperties assistantProperties;
    private final ObjectMapper objectMapper;

    /** 解析结果缓存 —— FAQ 是静态文件，没有理由每次请求都读盘。 */
    private volatile List<Entry> cache;

    public AssistantFallbackFaq(AssistantProperties assistantProperties, ObjectMapper objectMapper) {
        this.assistantProperties = assistantProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 一条 FAQ（只取兜底需要的字段）。
     *
     * @param id 条目 ID
     * @param question 标准问法
     * @param answer 标准答案
     * @param keywords 用户口语里会出现的短语
     * @param source 依据引用的文档路径
     * @param anchor 依据引用的标题（原文带 Markdown 标题符号）
     */
    public record Entry(String id, String question, String answer, List<String> keywords, String source,
                        String anchor) {

        /**
         * 依据引用，形如 {@code docs/api/03-order.md §1. 创建订单}。
         *
         * <p>与 Python 侧 {@code FaqEntry#sourceRef} **同口径** —— 两边给家属看的
         * 依据引用必须一模一样，否则降级模式与大模型模式的答案长得不一样。</p>
         */
        public String sourceRef() {
            if (source == null || source.isBlank()) {
                return "";
            }
            if (anchor == null || anchor.isBlank()) {
                return source;
            }
            String heading = anchor.strip().replaceAll("^#+\\s*", "");
            return source + " §" + heading;
        }
    }

    /**
     * 找出与问题最匹配的 FAQ 条目。
     *
     * @param question 用户问题
     * @return 命中的条目；没有命中返回 {@link Optional#empty()}
     */
    public Optional<Entry> bestMatch(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }
        List<Entry> entries = entries();
        Entry best = null;
        int bestHits = 0;
        for (Entry entry : entries) {
            int hits = 0;
            for (String keyword : entry.keywords()) {
                if (keyword != null && !keyword.isBlank() && question.contains(keyword)) {
                    hits++;
                }
            }
            // 命中数相同则取 ID 靠前者 —— 保证同一问题每次给同一个答案
            if (hits > bestHits || (hits > 0 && hits == bestHits && best != null
                    && entry.id().compareTo(best.id()) < 0)) {
                best = entry;
                bestHits = hits;
            }
        }
        return bestHits > 0 ? Optional.ofNullable(best) : Optional.empty();
    }

    /**
     * 载入 FAQ 条目。读不到时返回空列表（调用方据此抛 10002）。
     */
    public List<Entry> entries() {
        List<Entry> local = cache;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cache != null) {
                return cache;
            }
            List<Entry> loaded = load();
            cache = loaded;
            return loaded;
        }
    }

    private List<Entry> load() {
        Path path = resolvePath();
        if (!Files.isRegularFile(path)) {
            log.warn("助手兜底 FAQ 文件不存在：{} —— Python 服务不可用时将无法兜底", path);
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(path));
            if (!root.isArray()) {
                log.warn("助手兜底 FAQ 顶层不是数组：{}", path);
                return Collections.emptyList();
            }
            List<Entry> entries = new ArrayList<>();
            for (JsonNode node : root) {
                String id = node.path("id").asText("");
                String question = node.path("question").asText("");
                String answer = node.path("answer").asText("");
                String source = node.path("source").asText("");
                String anchor = node.path("anchor").asText("");
                if (id.isBlank() || answer.isBlank()) {
                    continue;
                }
                List<String> keywords = new ArrayList<>();
                JsonNode keywordNode = node.path("keywords");
                if (keywordNode.isArray()) {
                    keywordNode.forEach(item -> keywords.add(item.asText("")));
                }
                entries.add(new Entry(id, question, answer, keywords, source, anchor));
            }
            log.info("助手兜底 FAQ 已加载：{} 条（来源 {}）", entries.size(), path);
            return entries;
        } catch (IOException | RuntimeException e) {
            log.warn("助手兜底 FAQ 读取失败：{}（{}）", path, e.getMessage());
            return Collections.emptyList();
        }
    }

    private Path resolvePath() {
        String configured = assistantProperties.getFaqPath();
        if (configured == null || configured.isBlank()) {
            return Paths.get("ai-service", "knowledge", "faq.json");
        }
        return Paths.get(configured);
    }
}
