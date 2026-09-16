package org.company.nianglin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.OrderStatus;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ReviewCreateDTO;
import org.company.nianglin.entity.CompanionOrder;
import org.company.nianglin.entity.OrderReview;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.CompanionProfileMapper;
import org.company.nianglin.mapper.OrderReviewMapper;
import org.company.nianglin.mapper.ReviewReadMapper;
import org.company.nianglin.security.LoginUser;
import org.company.nianglin.service.impl.ReviewServiceImpl;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.CompanionScoreVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 评价服务单测（M7）。
 *
 * <p>本类盯的是三条<b>顺序敏感</b>的规则，顺序写反了功能照样「能跑」，但提示会把人带偏：</p>
 *
 * <ol>
 *   <li><b>「已评价」必须排在状态闸门之前</b>。评价成功后订单会被推进到
 *       {@code REVIEWED}，此时状态判断也会命中「非 COMPLETED」这一支 ——
 *       若顺序反了，重复评价会返回 6001「订单尚未完成」，而这张单其实早就完成并且评过了。
 *       实测过这个缺陷，所以把它固化成用例。</li>
 *   <li><b>归属先于角色</b>。{@code requireInvolved} 会放行 ADMIN，
 *       但管理员不该以「评价人」身份落库，所以紧接着还要比一次
 *       {@code familyId}，两次判断缺一不可。</li>
 *   <li><b>评分聚合走 SQL，不走内存</b>。即使把评价全查出来 {@code stream().average()}
 *       也能算出一样的数字，但那个数字会随分页窗口漂移。
 *       这里用「没有堆内存里的评价也能算出平均分」来锁死实现方式。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("评价服务：状态闸门顺序 / 归属与角色双判 / 评分聚合口径")
class ReviewServiceTest {

    private static final Long ORDER_ID = 1031L;
    private static final Long FAMILY_ID = 101L;
    private static final Long OTHER_FAMILY_ID = 102L;
    private static final Long COMPANION_ID = 307L;

    @Mock
    private OrderReviewMapper reviewMapper;

    @Mock
    private ReviewReadMapper reviewReadMapper;

    @Mock
    private CompanionProfileMapper companionProfileMapper;

    @Mock
    private OrderService orderService;

    @Mock
    private UserNameResolver userNameResolver;

    @Mock
    private StringRedisTemplate redisTemplate;

    private ReviewServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        service = new ReviewServiceImpl(reviewMapper, reviewReadMapper, companionProfileMapper,
                orderService, userNameResolver, redisTemplate, new ObjectMapper());
        loginAs(RoleConstants.FAMILY, FAMILY_ID);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /* ================================================================== */
    /* 1 · 提交评价：四道闸门的顺序                                          */
    /* ================================================================== */

    @Test
    @DisplayName("提交评价 · 家属评价别人下的单 → 3004（角色对但归属不对）")
    void createShouldRejectOtherFamily() {
        // 换一个家属登录：requireInvolved 对「订单相关方」是放行的，
        // 能拦住这次调用的只有紧随其后的 familyId 比对
        loginAs(RoleConstants.FAMILY, OTHER_FAMILY_ID);
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order(OrderStatus.COMPLETED));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(reviewDto(5, "服务挺好的")));

        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
        verify(reviewMapper, never()).insert(any(OrderReview.class));
    }

    @Test
    @DisplayName("提交评价 · 管理员即使通过了归属校验也不能当评价人 → 3004")
    void createShouldRejectAdminAsReviewer() {
        loginAs(RoleConstants.ADMIN, 1L);
        // requireInvolved 对管理员是放行的 —— 正是这一点让第二次 familyId 比对变得必要
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order(OrderStatus.COMPLETED));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(reviewDto(5, "服务挺好的")));

        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
        verify(reviewMapper, never()).insert(any(OrderReview.class));
    }

    @Test
    @DisplayName("提交评价 · 已评价过的单必须先报 6002，而不是 6001")
    void createShouldReportAlreadyReviewedBeforeStatusGate() {
        // 订单已经是 REVIEWED：若状态闸门先跑，这里会得到「订单尚未完成」这种明显错误的提示
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order(OrderStatus.REVIEWED));
        given(reviewMapper.selectCount(any())).willReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(reviewDto(5, "服务挺好的")));

        assertEquals(ResultCode.ORDER_ALREADY_REVIEWED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("提交评价 · 订单未完成 → 6001")
    void createShouldRejectUnfinishedOrder() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order(OrderStatus.IN_SERVICE));
        given(reviewMapper.selectCount(any())).willReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(reviewDto(5, "服务挺好的")));

        assertEquals(ResultCode.ORDER_NOT_COMPLETED.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("提交评价 · 已完成却没有陪诊员 → 500（数据异常不能变成一条 companion_id 为空的评价）")
    void createShouldFailFastOnMissingCompanion() {
        CompanionOrder broken = order(OrderStatus.COMPLETED);
        broken.setCompanionId(null);
        given(orderService.requireInvolved(ORDER_ID)).willReturn(broken);
        given(reviewMapper.selectCount(any())).willReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(reviewDto(5, "服务挺好的")));

        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), ex.getCode());
        verify(reviewMapper, never()).insert(any(OrderReview.class));
    }

    @Test
    @DisplayName("提交评价 · 文字少于 5 个字符 → 400（要写就写清楚，不写可以留空）")
    void createShouldRejectTooShortContent() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order(OrderStatus.COMPLETED));
        given(reviewMapper.selectCount(any())).willReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(reviewDto(5, "不错")));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("提交评价 · 命中敏感词 → 6003，且不落库")
    void createShouldRejectSensitiveWord() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order(OrderStatus.COMPLETED));
        given(reviewMapper.selectCount(any())).willReturn(0L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(reviewDto(1, "这个陪诊员就是骗子，态度很差")));

        assertEquals(ResultCode.CONTENT_SENSITIVE.getCode(), ex.getCode());
        verify(reviewMapper, never()).insert(any(OrderReview.class));
    }

    @Test
    @DisplayName("提交评价 · 成功：落库 + 推进订单 + 刷新评分快照，三件事都要发生")
    void createShouldPersistAndAdvanceOrder() {
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order(OrderStatus.COMPLETED));
        given(reviewMapper.selectCount(any())).willReturn(0L);
        given(reviewMapper.insert(any(OrderReview.class))).willReturn(1);
        given(reviewReadMapper.selectAverageScore(COMPANION_ID)).willReturn(new BigDecimal("4.50"));
        given(reviewReadMapper.selectScoreDistribution(COMPANION_ID)).willReturn(
                List.of(Map.of("score", 5, "cnt", 3L), Map.of("score", 4, "cnt", 1L)));

        ReviewCreateDTO dto = reviewDto(5, "小李很耐心，全程陪着老人");
        dto.setTags(List.of("准时", "耐心", "耐心"));
        dto.setIsAnonymous(true);
        service.create(dto);

        ArgumentCaptor<OrderReview> reviewCaptor = ArgumentCaptor.forClass(OrderReview.class);
        verify(reviewMapper).insert(reviewCaptor.capture());
        OrderReview saved = reviewCaptor.getValue();
        assertEquals(ORDER_ID, saved.getOrderId());
        assertEquals(FAMILY_ID, saved.getFamilyId(), "评价人必须是订单的下单家属");
        assertEquals(COMPANION_ID, saved.getCompanionId(), "被评价人必须来自订单关系，不接受前端传入");
        assertEquals(1, saved.getIsAnonymous());
        assertEquals(1, saved.getIsValid());
        assertEquals("[\"准时\",\"耐心\"]", saved.getTags(), "标签要去重后落库");

        // 订单状态由订单模块自己改：评价模块只发出「请推进」的信号
        verify(orderService).markReviewed(ORDER_ID);
        // 评分快照与评价记录在同一事务里刷新
        verify(companionProfileMapper).update(any(), any());
    }

    /* ================================================================== */
    /* 2 · 查询订单评价                                                      */
    /* ================================================================== */

    @Test
    @DisplayName("查询订单评价 · 无权查看时直接抛 3004，而不是静默返回 null")
    void byOrderShouldPropagateOwnershipFailure() {
        given(orderService.requireInvolved(ORDER_ID))
                .willThrow(new BusinessException(ResultCode.ORDER_NO_PERMISSION));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.byOrder(ORDER_ID));
        assertEquals(ResultCode.ORDER_NO_PERMISSION.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("查询订单评价 · 没评价过返回 null（与「不存在」「无权」是三种不同结果）")
    void byOrderShouldReturnNullWhenNotReviewed() {
        loginAs(RoleConstants.FAMILY, OTHER_FAMILY_ID);
        given(orderService.requireInvolved(ORDER_ID)).willReturn(order(OrderStatus.COMPLETED));
        given(reviewMapper.selectOne(any())).willReturn(null);

        assertNull(service.byOrder(ORDER_ID));
    }

    /* ================================================================== */
    /* 3 · 评分聚合：口径必须来自 SQL                                        */
    /* ================================================================== */

    @Test
    @DisplayName("评分聚合 · 无有效评价时返回 0.00 / 0 条，而不是 null")
    void scoreShouldReturnZeroWhenNoReview() {
        given(reviewReadMapper.selectAverageScore(COMPANION_ID)).willReturn(null);
        given(reviewReadMapper.selectScoreDistribution(COMPANION_ID)).willReturn(List.of());

        CompanionScoreVO vo = service.score(COMPANION_ID);

        assertEquals("0.00", vo.getAverageScore());
        assertEquals(0, vo.getTotalCount());
        assertEquals("0.00%", vo.getGoodRate());
        // 五个星级一个都不能少：前端要画等宽柱子，缺键会让柱位错位
        assertEquals(List.of("5", "4", "3", "2", "1"), List.copyOf(vo.getStarDistribution().keySet()));
    }

    @Test
    @DisplayName("评分聚合 · 平均分与好评率按数据库统计口径输出（不从内存里的评价算）")
    void scoreShouldMirrorSqlAggregation() {
        given(reviewReadMapper.selectAverageScore(COMPANION_ID)).willReturn(new BigDecimal("4.8"));
        given(reviewReadMapper.selectScoreDistribution(COMPANION_ID)).willReturn(
                List.of(Map.of("score", 5, "cnt", 30L),
                        Map.of("score", 4, "cnt", 5L),
                        Map.of("score", 1, "cnt", 1L)));

        CompanionScoreVO vo = service.score(COMPANION_ID);

        assertEquals("4.80", vo.getAverageScore());
        assertEquals(36, vo.getTotalCount());
        assertEquals(35L, vo.getStarDistribution().get("5") + vo.getStarDistribution().get("4"));
        assertEquals(0L, vo.getStarDistribution().get("3"), "缺失星级必须补 0 而不是缺键");
        assertEquals("97.22%", vo.getGoodRate(), "好评率 = 4 星及以上占比");
        // 关键：整条路径没有读任何「评价明细」，平均分只能来自 SQL
        verify(reviewMapper, never()).selectList(any());
    }

    /* ================================================================== */

    private CompanionOrder order(OrderStatus status) {
        CompanionOrder order = new CompanionOrder();
        order.setId(ORDER_ID);
        order.setOrderNo("NL20260820000031");
        order.setFamilyId(FAMILY_ID);
        order.setElderId(401L);
        order.setCompanionId(COMPANION_ID);
        order.setStatus(status.name());
        return order;
    }

    private ReviewCreateDTO reviewDto(int score, String content) {
        ReviewCreateDTO dto = new ReviewCreateDTO();
        dto.setOrderId(ORDER_ID);
        dto.setScore(score);
        dto.setContent(content);
        return dto;
    }

    private void loginAs(String role, Long userId) {
        LoginUser loginUser = new LoginUser(userId, "tester", role, 0, "jti",
                System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }
}
