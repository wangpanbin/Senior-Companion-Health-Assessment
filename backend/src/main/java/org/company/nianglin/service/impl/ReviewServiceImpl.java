package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RedisKeyConstants;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ReviewCreateDTO;
import org.company.nianglin.dto.ReviewQuery;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.CompanionProfile;
import org.company.nianglin.entity.OrderReview;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.OrderReviewMapper;
import org.company.nianglin.mapper.ReviewReadMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.OrderService;
import org.company.nianglin.service.ReviewService;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.util.SensitiveWordUtil;
import org.company.nianglin.vo.CompanionScoreVO;
import org.company.nianglin.vo.ReviewCreateResultVO;
import org.company.nianglin.vo.ReviewVO;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 评价服务实现。
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    /** 评分聚合缓存 TTL：写路径会主动失效，TTL 只是「万一漏删」的兜底 */
    private static final Duration SCORE_CACHE_TTL = Duration.ofMinutes(10);

    /** 标签的存储上限（与 {@code docs/api/06-review-complaint.md} §1 一致） */
    private static final int MAX_TAGS = 5;

    /** 单个标签的字符上限 */
    private static final int MAX_TAG_LENGTH = 10;

    /** 评价文字的最小长度：只填了 1–2 个字的多半是误触 */
    private static final int MIN_CONTENT_LENGTH = 5;

    private final OrderReviewMapper reviewMapper;
    private final ReviewReadMapper reviewReadMapper;
    private final CompanionProfileMapper companionProfileMapper;
    private final OrderService orderService;
    private final UserNameResolver userNameResolver;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /* ================================================================== */
    /* 1. 提交评价                                                         */
    /* ================================================================== */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReviewCreateResultVO create(ReviewCreateDTO dto) {
        LoginUser me = SecurityUtils.currentUser();

        CompanionOrder order = orderService.requireInvolved(dto.getOrderId());
        // requireInvolved 会放行 ADMIN，但管理员不该以评价人身份出现在评价里：
        // 评价人必须真的是这笔订单的下单家属
        if (!RoleConstants.FAMILY.equals(me.role()) || !me.userId().equals(order.getFamilyId())) {
            throw new BusinessException(ResultCode.ORDER_NO_PERMISSION);
        }

        // 「已评价」必须排在状态闸门之前。
        //
        // 评价成功后订单会被推进到 REVIEWED，于是重复调用时状态判断也会命中
        // 「不是 COMPLETED」这一支，返回 6001「订单尚未完成，不能评价」——
        // 而这张单其实早就完成并且评过了，这句提示把用户引向完全错误的方向。
        //
        // 原先的实现指望下面的 uk_order_review 唯一索引兜底，但索引在状态闸门之后，
        // 正常路径永远轮不到它（实测：重复评价返回 6001，与
        // docs/api/06-review-complaint.md 的 6002 契约不符）。
        // 唯一索引仍然保留，它的职责是挡住并发穿透 —— 两个请求同时通过这里的查询。
        Long reviewed = reviewMapper.selectCount(Wrappers.<OrderReview>lambdaQuery()
                .eq(OrderReview::getOrderId, order.getId()));
        if (reviewed != null && reviewed > 0) {
            throw new BusinessException(ResultCode.ORDER_ALREADY_REVIEWED);
        }

        if (!OrderStatus.COMPLETED.name().equals(order.getStatus())) {
            throw new BusinessException(ResultCode.ORDER_NOT_COMPLETED);
        }
        if (order.getCompanionId() == null) {
            // 已完成却没有陪诊员属于数据异常（正常流程里接单才会开始服务）。
            // 不能让它变成一条 companion_id 为空的评价，否则评分聚合会把它算到别人头上
            log.error("已完成订单缺少陪诊员，拒绝评价 | orderId={}", order.getId());
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "订单数据异常，请联系管理员");
        }

        String content = trimToNull(dto.getContent());
        if (content != null && content.length() < MIN_CONTENT_LENGTH) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "评价文字至少 " + MIN_CONTENT_LENGTH + " 个字符，或留空只打分");
        }
        String hit = SensitiveWordUtil.firstHit(content);
        if (hit != null) {
            log.info("评价命中敏感词，已拒绝 | orderId={} | hit={}", dto.getOrderId(), hit);
            throw new BusinessException(ResultCode.CONTENT_SENSITIVE, "评价内容包含敏感词：" + hit);
        }

        List<String> tags = normalizeTags(dto.getTags());

        OrderReview review = new OrderReview();
        review.setOrderId(order.getId());
        review.setOrderNo(order.getOrderNo());
        review.setFamilyId(me.userId());
        review.setElderId(order.getElderId());
        review.setCompanionId(order.getCompanionId());
        review.setScore(dto.getScore());
        review.setTags(writeTags(tags));
        review.setContent(content);
        review.setIsAnonymous(Boolean.TRUE.equals(dto.getIsAnonymous()) ? 1 : 0);
        review.setIsValid(1);

        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            // uk_order_review(order_id) 拦住了重复评价。
            // 这一层是必须的：前端禁用按钮挡不住双开页面与接口重放
            log.info("重复评价被唯一索引拦截 | orderId={} | familyId={}", order.getId(), me.userId());
            throw new BusinessException(ResultCode.ORDER_ALREADY_REVIEWED);
        }

        // 订单状态由订单模块自己改，评价模块只发出「请推进」的信号
        orderService.markReviewed(order.getId());

        refreshCompanionScore(order.getCompanionId());

        log.info("评价已提交 | reviewId={} | orderId={} | companionId={} | score={} | 匿名={}",
                review.getId(), order.getId(), order.getCompanionId(), dto.getScore(), review.getIsAnonymous());
        return ReviewCreateResultVO.of(review.getId(), OrderStatus.REVIEWED);
    }

    /* ================================================================== */
    /* 2. 查询订单评价                                                     */
    /* ================================================================== */

    @Override
    public ReviewVO byOrder(Long orderId) {
        // 归属校验先做：未评价时也会 3004，而不是「静默返回 null」，
        // 否则「订单不存在」与「订单没评价」在接口上无法区分
        orderService.requireInvolved(orderId);

        OrderReview review = reviewMapper.selectOne(Wrappers.<OrderReview>lambdaQuery()
                .eq(OrderReview::getOrderId, orderId)
                .last("LIMIT 1"));
        if (review == null) {
            return null;
        }
        return toVO(review);
    }

    /* ================================================================== */
    /* 3. 陪诊员评价列表                                                   */
    /* ================================================================== */

    @Override
    public PageResult<ReviewVO> byCompanion(Long companionId, ReviewQuery query) {
        var wrapper = Wrappers.<OrderReview>lambdaQuery()
                .eq(OrderReview::getCompanionId, companionId)
                // 被管理员判定无效的评价不出现在公开列表里：
                // 它仍然留在库中（§2 的评价人自己能查到），但不再参与对外展示
                .eq(OrderReview::getIsValid, 1);

        if (query.getMinScore() != null) {
            wrapper.ge(OrderReview::getScore, query.getMinScore());
        }
        if (Boolean.TRUE.equals(query.getHasContent())) {
            // 用 <> '' 判断「有文字」而不是 isNotNull：
            // 历史数据里可能存在空串，而空串在界面上与 null 一样是一行空白
            wrapper.isNotNull(OrderReview::getContent).ne(OrderReview::getContent, "");
        }
        wrapper.orderByDesc(OrderReview::getCreateTime).orderByDesc(OrderReview::getId);

        Page<OrderReview> page = reviewMapper.selectPage(query.toMpPage(), wrapper);

        Set<Long> userIds = new LinkedHashSet<>();
        for (OrderReview review : page.getRecords()) {
            userIds.add(review.getFamilyId());
            userIds.add(review.getCompanionId());
        }
        Map<Long, String> names = userNameResolver.resolveAll(userIds);

        List<ReviewVO> records = new ArrayList<>(page.getRecords().size());
        for (OrderReview review : page.getRecords()) {
            records.add(buildVO(review,
                    names.get(review.getFamilyId()),
                    names.get(review.getCompanionId())));
        }
        return PageResult.of(page, records);
    }

    /* ================================================================== */
    /* 4. 评分聚合                                                         */
    /* ================================================================== */

    @Override
    public CompanionScoreVO score(Long companionId) {
        CompanionScoreVO cached = readCachedScore(companionId);
        if (cached != null) {
            return cached;
        }

        CompanionScoreVO fresh = aggregate(companionId);
        writeCachedScore(companionId, fresh);
        return fresh;
    }

    /**
     * 从数据库实时聚合。
     *
     * <p>两个查询分别对应文档 §4 的两条口径要求：
     * 平均分由数据库 {@code AVG} 算出（与验收用的手工 SQL 同构），
     * 分布由 {@code GROUP BY score} 算出。<b>不用「把评价全查出来在内存里算」</b> ——
     * 那样既随数据量变慢，也让「排除无效评价」这条口径散落在 Java 代码里。</p>
     */
    private CompanionScoreVO aggregate(Long companionId) {
        BigDecimal average = reviewReadMapper.selectAverageScore(companionId);
        Map<Integer, Long> distribution = new HashMap<>(5);
        long total = 0L;
        for (Map<String, Object> row : reviewReadMapper.selectScoreDistribution(companionId)) {
            Object score = row.get("score");
            Object cnt = row.get("cnt");
            if (score == null || cnt == null) {
                continue;
            }
            long count = ((Number) cnt).longValue();
            distribution.put(((Number) score).intValue(), count);
            total += count;
        }
        return CompanionScoreVO.of(companionId, average, total, distribution);
    }

    /**
     * 评价写入后同步刷新陪诊员的评分快照（{@code companion_profile.score / review_count}）
     * 并删掉 Redis 缓存。
     *
     * <p>刻意把这一步放在<b>事务内</b>：评分快照与评价记录要么一起生效，
     * 要么一起回滚。若把它挪到事务提交后，评价写成功而快照刷新失败时，
     * 陪诊员的资料页会长期显示旧分数，且没有任何补偿机制。</p>
     *
     * <p>Redis 删除失败不影响事务：缓存有自己的 TTL，最坏 10 分钟后自愈。</p>
     */
    @Override
    public void refreshCompanionScore(Long companionId) {
        CompanionScoreVO score = aggregate(companionId);
        companionProfileMapper.update(null, Wrappers.<CompanionProfile>lambdaUpdate()
                .eq(CompanionProfile::getUserId, companionId)
                .set(CompanionProfile::getScore, new BigDecimal(score.getAverageScore()))
                .set(CompanionProfile::getReviewCount, score.getTotalCount()));

        try {
            redisTemplate.delete(RedisKeyConstants.companionScore(companionId));
        } catch (Exception e) {
            log.warn("评分缓存失效失败（将由 TTL 兜底） | companionId={} | {}", companionId, e.getMessage());
        }
    }
    /* ================================================================== */
    /* 内部：缓存                                                          */
    /* ================================================================== */

    private CompanionScoreVO readCachedScore(Long companionId) {
        try {
            String json = redisTemplate.opsForValue().get(RedisKeyConstants.companionScore(companionId));
            if (!StringUtils.hasText(json)) {
                return null;
            }
            return objectMapper.readValue(json, CompanionScoreVO.class);
        } catch (Exception e) {
            // 缓存反序列化失败（例如类结构改过）时删掉它并回落到数据库，
            // 而不是把异常抛给调用方 —— 缓存永远不该成为故障源
            log.warn("评分缓存读取失败，回退数据库 | companionId={} | {}", companionId, e.getMessage());
            try {
                redisTemplate.delete(RedisKeyConstants.companionScore(companionId));
            } catch (Exception ignored) {
                // 删不掉也无所谓，TTL 会处理
            }
            return null;
        }
    }

    private void writeCachedScore(Long companionId, CompanionScoreVO score) {
        try {
            redisTemplate.opsForValue().set(RedisKeyConstants.companionScore(companionId),
                    objectMapper.writeValueAsString(score), SCORE_CACHE_TTL);
        } catch (Exception e) {
            log.warn("评分缓存写入失败（不影响本次返回） | companionId={} | {}", companionId, e.getMessage());
        }
    }

    /* ================================================================== */
    /* 内部：转换与校验                                                    */
    /* ================================================================== */

    private ReviewVO toVO(OrderReview review) {
        Map<Long, String> names = userNameResolver.resolveAll(
                List.of(review.getFamilyId(), review.getCompanionId()));
        return buildVO(review, names.get(review.getFamilyId()), names.get(review.getCompanionId()));
    }

    private ReviewVO buildVO(OrderReview review, String familyName, String companionName) {
        return ReviewVO.of(review, familyName, companionName, parseTags(review.getTags()));
    }

    /**
     * 标签规范化：去空白、去重、去空串，超过上限直接截断。
     *
     * <p>去重是必要的：{@code ["耐心","耐心"]} 存进库里，前端会渲染两个一样的标签，
     * 看起来像是重复提交了。截断而不是报错是因为上限（5 个）已经由注解挡住，
     * 真走到这里说明客户端绕过了校验，多出来的丢掉比整个请求失败更合适。</p>
     */
    private List<String> normalizeTags(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String tag : raw) {
            if (!StringUtils.hasText(tag)) {
                continue;
            }
            String normalized = tag.trim();
            if (normalized.length() > MAX_TAG_LENGTH) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "单个标签不能超过 " + MAX_TAG_LENGTH + " 个字符");
            }
            distinct.add(normalized);
            if (distinct.size() >= MAX_TAGS) {
                break;
            }
        }
        return new ArrayList<>(distinct);
    }

    private String writeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            log.error("评价标签序列化失败 | {}", e.getMessage());
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "保存评价标签失败，请稍后重试");
        }
    }

    /** 解析标签 JSON；内容损坏时按空处理，不让一条脏数据把整个列表打挂 */
    private List<String> parseTags(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            List<String> parsed = objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {
                    });
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            log.warn("评价标签 JSON 解析失败，已按空处理 | len={} | {}", json.length(), e.getMessage());
            return List.of();
        }
    }

    /** 空白串归一成 {@code null}：库里不该出现「看起来有内容、其实是空格」的评价 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
