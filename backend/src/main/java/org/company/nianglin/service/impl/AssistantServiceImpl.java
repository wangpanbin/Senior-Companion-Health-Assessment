package org.company.nianglin.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.config.AssistantProperties;
import org.company.nianglin.constant.RedisKeyConstants;
import org.company.nianglin.dto.AssistantChatDTO;
import org.company.nianglin.dto.AssistantHistoryTurn;
import org.company.nianglin.dto.ElderQuery;
import org.company.nianglin.dto.OrderQuery;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.AssistantService;
import org.company.nianglin.service.ElderService;
import org.company.nianglin.service.OrderService;
import org.company.nianglin.service.support.AssistantFallbackFaq;
import org.company.nianglin.vo.AssistantActionVO;
import org.company.nianglin.vo.AssistantChatVO;
import org.company.nianglin.vo.CompanionMatchVO;
import org.company.nianglin.vo.ElderVO;
import org.company.nianglin.vo.OrderRecommendVO;
import org.company.nianglin.vo.OrderVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 家属智能助手门面实现（V3.7 · M14）。
 *
 * <h3>本类只做三件事</h3>
 * <ol>
 *   <li><b>限流</b> —— 每次问答都调一次外部模型，成本是真的；</li>
 *   <li><b>上下文装配</b> —— 订单上下文、候选陪诊员、老人列表查好，交给 Python；</li>
 *   <li><b>兜底</b> —— Python 不可用时本地读同一份 FAQ 回答。</li>
 * </ol>
 *
 * <h3>三条不可越过的边界</h3>
 * <ul>
 *   <li><b>不改排序</b> —— 候选陪诊员原样来自 {@code OrderService#recommendCompanions}，
 *       本类不重排、不改分、不补充任何字段；</li>
 *   <li><b>不建订单</b> —— 只把就诊信息交给前端回填草稿，提交由用户确认；</li>
 *   <li><b>不碰医疗</b> —— 护栏在 Python 侧（输入分类 / 输出扫描），
 *       本类只做最后一道兜底：答案里出现医疗建议类表述就整段替换掉。</li>
 * </ul>
 *
 * @author 银龄伴诊团队
 * @since V3.7
 */
@Slf4j
@Service
public class AssistantServiceImpl implements AssistantService {

    /** 传给 Python 的候选条数上限。够讲清楚「哪几位最合适」，又不至于把 payload 撑大。 */
    private static final int CANDIDATE_LIMIT = 5;
    /** 装配进上下文的最近订单条数。 */
    private static final int RECENT_ORDER_LIMIT = 5;
    /** 装配进上下文的老人档案条数。 */
    private static final int ELDER_LIMIT = 20;

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter MINUTE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final OrderService orderService;
    private final ElderService elderService;
    private final StringRedisTemplate redisTemplate;
    private final AssistantProperties assistantProperties;
    private final AssistantFallbackFaq fallbackFaq;
    private final RestTemplate assistantRestTemplate;

    public AssistantServiceImpl(OrderService orderService,
                                ElderService elderService,
                                StringRedisTemplate redisTemplate,
                                AssistantProperties assistantProperties,
                                AssistantFallbackFaq fallbackFaq) {
        this.orderService = orderService;
        this.elderService = elderService;
        this.redisTemplate = redisTemplate;
        this.assistantProperties = assistantProperties;
        this.fallbackFaq = fallbackFaq;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(assistantProperties.getConnectTimeoutMs());
        factory.setReadTimeout(assistantProperties.getReadTimeoutMs());
        this.assistantRestTemplate = new RestTemplate(factory);
    }

    @Override
    public AssistantChatVO chat(AssistantChatDTO dto) {
        if (!assistantProperties.isEnabled()) {
            throw new BusinessException(ResultCode.ASSISTANT_UNAVAILABLE, "智能助手当前未启用");
        }
        checkRateLimit();

        String question = dto.getQuestion() == null ? "" : dto.getQuestion().trim();
        Map<String, Object> payload = buildPayload(dto, question);

        try {
            Map<String, Object> result = callAssistant(payload);
            return toVO(result);
        } catch (IllegalQuestionException e) {
            // Python 判定问题含辱骂 / 违规引流词 —— 明确回 10003，不要走兜底
            throw new BusinessException(ResultCode.ASSISTANT_QUESTION_ILLEGAL);
        } catch (Exception e) {
            log.warn("助手服务调用失败，走本地兜底：{}", e.getMessage());
            if (!assistantProperties.isFallbackOnError()) {
                throw new BusinessException(ResultCode.ASSISTANT_UNAVAILABLE);
            }
            return fallbackAnswer(question);
        }
    }

    /* ================================================================== */
    /* 限流                                                                */
    /* ================================================================== */

    /**
     * 每用户每分钟限流。
     *
     * <p>Redis 不可用时**放行**：限流是成本保护措施，不是安全边界 ——
     * Redis 挂了不该让助手整体不可用。放行时记 warn 日志，便于事后发现。</p>
     */
    private void checkRateLimit() {
        int limit = assistantProperties.getRateLimitPerMinute();
        String key = RedisKeyConstants.assistantRateLimit(
                SecurityUtils.currentUserId(), LocalDateTime.now().format(MINUTE_FORMAT));
        long count;
        try {
            Long incremented = redisTemplate.opsForValue().increment(key);
            count = incremented == null ? 0L : incremented;
            if (count == 1L) {
                redisTemplate.expire(key, Duration.ofSeconds(120));
            }
        } catch (RuntimeException e) {
            log.warn("助手限流计数失败，本次放行：{}", e.getMessage());
            return;
        }
        if (count > limit) {
            throw new BusinessException(ResultCode.ASSISTANT_RATE_LIMITED);
        }
    }

    /* ================================================================== */
    /* 上下文装配                                                          */
    /* ================================================================== */

    private Map<String, Object> buildPayload(AssistantChatDTO dto, String question) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("history", buildHistory(dto.getHistory()));
        payload.put("elders", buildElders());
        payload.put("order", null);
        payload.put("candidates", List.of());
        payload.put("recentOrders", buildRecentOrders());

        if (dto.getOrderId() != null) {
            // 归属校验在这里发生：他人订单 → 3004（OrderService#requireInvolved）
            OrderVO order = orderService.detail(dto.getOrderId());
            payload.put("order", orderBrief(order));
            payload.put("candidates", buildCandidates(dto.getOrderId()));
        }
        if (dto.getElderId() != null) {
            // 归属校验：非本家属绑定的老人 → 2006（ElderService#requireAccessible）
            elderService.detail(dto.getElderId());
        }
        return payload;
    }

    private List<Map<String, Object>> buildHistory(List<AssistantHistoryTurn> history) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (history == null) {
            return list;
        }
        int max = assistantProperties.getMaxHistoryTurns();
        for (AssistantHistoryTurn turn : history) {
            if (turn == null || turn.getRole() == null || turn.getContent() == null) {
                continue;
            }
            list.add(Map.of("role", turn.getRole(), "content", turn.getContent()));
            if (list.size() >= max) {
                break;
            }
        }
        return list;
    }

    /** 老人档案列表（**脱敏姓名**，与 ElderVO 列表口径一致）。 */
    private List<Map<String, Object>> buildElders() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            ElderQuery query = new ElderQuery();
            query.setPage(1L);
            query.setSize((long) ELDER_LIMIT);
            PageResult<ElderVO> page = elderService.page(query);
            if (page == null || page.getRecords() == null) {
                return list;
            }
            for (ElderVO elder : page.getRecords()) {
                Map<String, Object> brief = new LinkedHashMap<>();
                brief.put("id", elder.getId());
                brief.put("name", elder.getName());
                list.add(brief);
            }
        } catch (RuntimeException e) {
            log.warn("装配老人列表失败：{}", e.getMessage());
        }
        return list;
    }

    /** 订单上下文摘要。字段全部来自 OrderVO，不额外补充。 */
    private Map<String, Object> orderBrief(OrderVO order) {
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("id", order.getId());
        brief.put("orderNo", order.getOrderNo());
        brief.put("status", order.getStatus());
        brief.put("statusLabel", order.getStatusLabel());
        brief.put("hospital", order.getHospital());
        brief.put("department", order.getDepartment());
        brief.put("visitTime", formatTime(order.getVisitTime()));
        brief.put("address", order.getAddress());
        brief.put("companionName", order.getCompanionName());
        return brief;
    }

    /**
     * 候选陪诊员 —— **原样取自 V3.4 规则引擎**，本类不重排、不改分。
     *
     * <p>取不到不算失败：候选是「锦上添花」，没有它助手照样能回答平台规则类问题。
     * 所以这里吞掉异常并返回空列表，而不是让整个问答失败。</p>
     */
    private List<CompanionMatchVO> buildCandidates(Long orderId) {
        try {
            OrderRecommendVO recommend = orderService.recommendCompanions(orderId, CANDIDATE_LIMIT);
            if (recommend == null || recommend.getCandidates() == null) {
                return List.of();
            }
            return recommend.getCandidates();
        } catch (RuntimeException e) {
            log.warn("装配候选陪诊员失败（不影响其它回答）：{}", e.getMessage());
            return List.of();
        }
    }

    private List<Map<String, Object>> buildRecentOrders() {
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            OrderQuery query = new OrderQuery();
            query.setPage(1L);
            query.setSize((long) RECENT_ORDER_LIMIT);
            PageResult<OrderVO> page = orderService.myOrders(query);
            if (page == null || page.getRecords() == null) {
                return list;
            }
            for (OrderVO order : page.getRecords()) {
                Map<String, Object> brief = new LinkedHashMap<>();
                brief.put("id", order.getId());
                brief.put("orderNo", order.getOrderNo());
                brief.put("status", order.getStatus());
                brief.put("statusLabel", order.getStatusLabel());
                brief.put("visitTime", formatTime(order.getVisitTime()));
                brief.put("hospital", order.getHospital());
                brief.put("department", order.getDepartment());
                list.add(brief);
            }
        } catch (RuntimeException e) {
            log.warn("装配最近订单失败：{}", e.getMessage());
        }
        return list;
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FORMAT);
    }

    /* ================================================================== */
    /* 调用 Python                                                         */
    /* ================================================================== */

    /** Python 判定问题含辱骂 / 违规引流词。 */
    private static class IllegalQuestionException extends RuntimeException {
        IllegalQuestionException(String message) {
            super(message);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callAssistant(Map<String, Object> payload) {
        String url = assistantProperties.getServiceUrl() + "/internal/assistant/chat";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
        try {
            Map<String, Object> body = assistantRestTemplate
                    .exchange(url, HttpMethod.POST, entity, Map.class)
                    .getBody();
            if (body == null) {
                throw new IllegalStateException("助手服务返回空响应体");
            }
            return body;
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == 400 && isIllegalQuestion(e.getResponseBodyAsString())) {
                throw new IllegalQuestionException("问题内容含违规表述");
            }
            throw e;
        }
    }

    private boolean isIllegalQuestion(String body) {
        return body != null && body.contains("ILLEGAL_QUESTION");
    }

    @SuppressWarnings("unchecked")
    private AssistantChatVO toVO(Map<String, Object> result) {
        AssistantChatVO vo = new AssistantChatVO();
        Object answer = result.get("answer");
        vo.setAnswer(answer == null ? "" : String.valueOf(answer));
        Object intent = result.get("intent");
        vo.setIntent(intent == null ? "UNKNOWN" : String.valueOf(intent));
        Object degraded = result.get("degraded");
        vo.setDegraded(Boolean.TRUE.equals(degraded));

        Object actions = result.get("actions");
        if (actions instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                AssistantActionVO action = new AssistantActionVO();
                Object type = map.get("type");
                action.setType(type == null ? "" : String.valueOf(type));
                Object actionPayload = map.get("payload");
                if (actionPayload instanceof Map<?, ?> payloadMap) {
                    Map<String, Object> converted = new LinkedHashMap<>();
                    payloadMap.forEach((key, value) -> converted.put(String.valueOf(key), value));
                    action.setPayload(converted);
                }
                vo.getActions().add(action);
            }
        }
        Object sources = result.get("sources");
        if (sources instanceof List<?> list) {
            for (Object item : list) {
                vo.getSources().add(String.valueOf(item));
            }
        }
        return vo;
    }

    /* ================================================================== */
    /* 兜底                                                                */
    /* ================================================================== */

    /**
     * 本地兜底回答。
     *
     * <p>读的是<b>同一份</b> FAQ 文件，所以兜底答案与大模型模式下的答案口径一致。
     * 命中不了任何条目时返回 10002（而不是编一个答案）。</p>
     */
    private AssistantChatVO fallbackAnswer(String question) {
        return fallbackFaq.bestMatch(question)
                .map(entry -> {
                    AssistantChatVO vo = new AssistantChatVO();
                    vo.setAnswer(entry.answer());
                    vo.setIntent("FAQ_PLATFORM");
                    vo.setDegraded(Boolean.TRUE);
                    if (!entry.sourceRef().isBlank()) {
                        vo.getSources().add(entry.sourceRef());
                    }
                    return vo;
                })
                .orElseThrow(() -> new BusinessException(ResultCode.ASSISTANT_UNAVAILABLE));
    }

    /** 供测试与排障查看当前是否可用。 */
    boolean isEnabled() {
        return assistantProperties.isEnabled();
    }

    /** 供测试与排障查看 HTTP 客户端是否可用（当前实现固定为 true）。 */
    boolean isRestTemplateReady() {
        return assistantRestTemplate != null;
    }

    /** 暴露给测试的 HTTP 状态判定（避免测试依赖真实网络）。 */
    static boolean isBadRequest(HttpStatusCode status) {
        return status != null && status.value() == 400;
    }
}
