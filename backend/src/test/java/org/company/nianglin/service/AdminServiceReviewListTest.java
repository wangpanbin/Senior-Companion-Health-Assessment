package org.company.nianglin.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.dto.AdminReviewQuery;
import org.company.nianglin.entity.OrderReview;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.OrderReviewMapper;
import org.company.nianglin.service.impl.AdminServiceImpl;
import org.company.nianglin.service.support.UserNameResolver;
import org.company.nianglin.support.MybatisLambdaCache;
import org.company.nianglin.vo.AdminReviewVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 评价巡查列表单测（E4）。
 *
 * <p>四个必须钉死的性质：</p>
 *
 * <ol>
 *   <li><b>能翻出已被裁定无效的评价</b> —— 这是本端点存在的全部理由。
 *       公开链路 {@code GET /api/review/companion/{id}} 硬编码 {@code eq(is_valid, 1)}，
 *       管理员用它永远看不到 {@code is_valid = 0} 的记录；
 *       「裁完就从列表里消失」的评价等于从来没被巡查过；</li>
 *   <li><b>isValid 三态语义</b> —— 不传 = 全部 / {@code true} = 只看有效 /
 *       {@code false} = 只看已被裁定无效。三态若塌成两态，
 *       「只看无效」就没法表达，巡查页退化成公开列表的复制品；</li>
 *   <li><b>is_valid IS NULL 视为有效</b> —— V1 老数据没有这一列，
 *       兜底口径必须与 {@code ReviewVO.of} 一致，否则同一评价在两个页面上一个有效一个无效；</li>
 *   <li><b>列表脱敏</b> —— 订单详情的全名豁免不适用于列表。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since E4
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("评价巡查列表：能筛出无效评价 / isValid 三态 / 列表脱敏")
class AdminServiceReviewListTest {

    private static final Long REVIEW_ID = 30001L;
    private static final Long FAMILY_ID = 101L;
    private static final Long COMPANION_ID = 307L;

    @Mock
    private OrderReviewMapper orderReviewMapper;

    @Mock
    private UserNameResolver userNameResolver;

    private AdminServiceImpl service;

    @BeforeEach
    void setUp() {
        MybatisLambdaCache.warmUp();
        // 未用到的依赖传 null：本类只走评价巡查这一条只读路径。
        // 第 11 位是 orderReviewMapper（E4 新增的构造器参数）。
        service = new AdminServiceImpl(null, null, null, null, null, null,
                null, null, null, null, orderReviewMapper, null,
                null, null, userNameResolver, null, null, null,
                new ObjectMapper());
    }

    /* ================================================================== */
    /* 1 · 有效性三态                                                       */
    /* ================================================================== */

    @Test
    @DisplayName("列表 · 不传 isValid 时不加任何有效性条件（全部可见）")
    void reviewListShouldNotFilterValidityByDefault() {
        given(orderReviewMapper.selectPage(any(), any())).willReturn(pageOf());

        service.reviewList(new AdminReviewQuery());

        assertFalse(whereClause().contains("is_valid"),
                "缺省必须不加有效性条件，实际：" + whereClause());
    }

    @Test
    @DisplayName("列表 · isValid=true 时只查有效评价，且把 is_valid IS NULL 也算作有效")
    void reviewListShouldFilterOnlyValid() {
        given(orderReviewMapper.selectPage(any(), any())).willReturn(pageOf());

        AdminReviewQuery query = new AdminReviewQuery();
        query.setIsValid(Boolean.TRUE);
        service.reviewList(query);

        String where = whereClause();
        assertTrue(where.contains("is_valid = #{ew.paramNameValuePairs"), where);
        // V1 老数据 is_valid 为 NULL，必须与 ReviewVO.of 的兜底一致
        assertTrue(where.contains("is_valid IS NULL"), "老数据（is_valid IS NULL）必须算有效：" + where);
    }

    @Test
    @DisplayName("列表 · isValid=false 时只查已被裁定无效的评价")
    void reviewListShouldFilterOnlyInvalid() {
        given(orderReviewMapper.selectPage(any(), any())).willReturn(pageOf());

        AdminReviewQuery query = new AdminReviewQuery();
        query.setIsValid(Boolean.FALSE);
        service.reviewList(query);

        String where = whereClause();
        assertTrue(where.contains("is_valid = #{ew.paramNameValuePairs"), where);
        assertFalse(where.contains("is_valid IS NULL"),
                "只看无效时不该把 NULL 拉进来：" + where);
        assertTrue(queryParams().containsValue(0), "无效的取值必须是 0：" + queryParams());
    }

    /* ================================================================== */
    /* 2 · 其它筛选维度                                                     */
    /* ================================================================== */

    @Test
    @DisplayName("列表 · 陪诊员 / 评分区间 / 关键字三态筛选都能落到 SQL 上")
    void reviewListShouldApplyAllFilters() {
        given(orderReviewMapper.selectPage(any(), any())).willReturn(pageOf());

        AdminReviewQuery query = new AdminReviewQuery();
        query.setCompanionId(COMPANION_ID);
        query.setMinScore(2);
        query.setMaxScore(4);
        query.setKeyword("NL2026");
        service.reviewList(query);

        Map<Object, Object> params = queryParams();
        assertTrue(params.containsValue(COMPANION_ID), "陪诊员条件没落上：" + params);
        assertTrue(params.containsValue(2), "评分下限没落上：" + params);
        assertTrue(params.containsValue(4), "评分上限没落上：" + params);
        assertTrue(params.containsValue("%NL2026%"),
                "关键字没落上（LIKE 的值带通配符）：" + params);
    }

    @Test
    @DisplayName("列表 · 日期区间倒置时直接 400，且不查库")
    void reviewListShouldRejectInvertedDateRange() {
        AdminReviewQuery query = new AdminReviewQuery();
        query.setStartDate("2026-10-01");
        query.setEndDate("2026-09-01");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reviewList(query));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        // 静默忽略会让管理员以为筛过的结果就是全部
        verify(orderReviewMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("列表 · 排序固定为评价时间倒序 + id 倒序")
    void reviewListShouldUseFixedOrder() {
        given(orderReviewMapper.selectPage(any(), any())).willReturn(pageOf());

        service.reviewList(new AdminReviewQuery());

        String sql = capturedSql();
        assertTrue(sql.contains("ORDER BY"), sql);
        assertTrue(sql.contains("create_time DESC"), "评价时间必须倒序，实际：" + sql);
        assertTrue(sql.contains("id DESC"), "同级要 id 倒序兜底，实际：" + sql);
    }

    /* ================================================================== */
    /* 3 · 脱敏与空态                                                       */
    /* ================================================================== */

    @Test
    @DisplayName("列表 · 家属与陪诊员姓名一律脱敏")
    void reviewListShouldMaskNames() {
        given(orderReviewMapper.selectPage(any(), any())).willReturn(pageOf(validReview()));
        given(userNameResolver.resolveAll(any())).willReturn(Map.of(
                FAMILY_ID, "张伟",
                COMPANION_ID, "黄海涛"));

        AdminReviewVO item = service.reviewList(new AdminReviewQuery()).getRecords().get(0);

        // MaskUtil.mask(name, 1, 1)：保留首尾各 1 位。
        // ⚠️ 当「首+尾 >= 长度」时退化为「只留首字 + 遮蔽其余」（保证至少遮 1 位）：
        //    两字名「张伟」→ 张*，三字名「黄海涛」→ 黄*涛
        assertEquals("张*", item.getFamilyName());
        assertEquals("黄*涛", item.getCompanionName());
    }

    @Test
    @DisplayName("列表 · 匿名评价显示固定串，不泄露真实姓名")
    void reviewListShouldHideAnonymousFamilyName() {
        OrderReview review = validReview();
        review.setIsAnonymous(1);
        given(orderReviewMapper.selectPage(any(), any())).willReturn(pageOf(review));
        given(userNameResolver.resolveAll(any())).willReturn(Map.of(
                FAMILY_ID, "张伟",
                COMPANION_ID, "黄海涛"));

        AdminReviewVO item = service.reviewList(new AdminReviewQuery()).getRecords().get(0);

        assertEquals("匿***", item.getFamilyName());
        assertTrue(item.getIsAnonymous());
    }

    @Test
    @DisplayName("列表 · is_valid=0 的记录 valid 字段为 false（这是巡查页的核心可见性）")
    void reviewListShouldExposeInvalidFlag() {
        OrderReview review = validReview();
        review.setIsValid(0);
        given(orderReviewMapper.selectPage(any(), any())).willReturn(pageOf(review));
        given(userNameResolver.resolveAll(any())).willReturn(Map.of(
                FAMILY_ID, "张伟",
                COMPANION_ID, "黄海涛"));

        AdminReviewVO item = service.reviewList(new AdminReviewQuery()).getRecords().get(0);

        assertFalse(item.getIsValid(), "已被裁定无效的评价必须能被看出来");
    }

    @Test
    @DisplayName("列表 · is_valid 为 NULL 的老数据视为有效")
    void reviewListShouldTreatNullIsValidAsValid() {
        OrderReview review = validReview();
        review.setIsValid(null);
        given(orderReviewMapper.selectPage(any(), any())).willReturn(pageOf(review));
        given(userNameResolver.resolveAll(any())).willReturn(Map.of(
                FAMILY_ID, "张伟",
                COMPANION_ID, "黄海涛"));

        AdminReviewVO item = service.reviewList(new AdminReviewQuery()).getRecords().get(0);

        assertTrue(item.getIsValid(), "老数据（is_valid IS NULL）必须算有效");
    }

    @Test
    @DisplayName("列表 · 空数据返回空列表，且不触发姓名批量查询")
    void reviewListShouldReturnEmptyListWithoutResolvingNames() {
        given(orderReviewMapper.selectPage(any(), any())).willReturn(pageOf());

        assertEquals(0, service.reviewList(new AdminReviewQuery()).getRecords().size());
        verify(userNameResolver, never()).resolveAll(any());
        verify(orderReviewMapper, times(1)).selectPage(any(), any());
    }

    @Test
    @DisplayName("列表 · 未回复的评价 companionReply / replyTime 为 null（不是空串）")
    void reviewListShouldKeepNullReplyFields() {
        OrderReview review = validReview();
        review.setCompanionReply(null);
        review.setReplyTime(null);
        given(orderReviewMapper.selectPage(any(), any())).willReturn(pageOf(review));
        given(userNameResolver.resolveAll(any())).willReturn(Map.of(
                FAMILY_ID, "张伟",
                COMPANION_ID, "黄海涛"));

        AdminReviewVO item = service.reviewList(new AdminReviewQuery()).getRecords().get(0);

        assertNull(item.getCompanionReply());
        assertNull(item.getReplyTime());
    }

    /* ================================================================== */
    /* 测试夹具                                                            */
    /* ================================================================== */

    private static OrderReview validReview() {
        OrderReview review = new OrderReview();
        review.setId(REVIEW_ID);
        review.setOrderId(1031L);
        review.setOrderNo("NL20260820000031");
        review.setFamilyId(FAMILY_ID);
        review.setElderId(401L);
        review.setCompanionId(COMPANION_ID);
        review.setScore(5);
        review.setTags("[\"准时\",\"耐心\"]");
        review.setContent("小李很耐心，全程陪着老人。");
        review.setIsAnonymous(0);
        review.setCompanionReply("感谢认可，后续会继续保持。");
        review.setReplyTime(LocalDateTime.of(2026, 9, 14, 20, 0));
        review.setIsValid(1);
        review.setCreateTime(LocalDateTime.of(2026, 9, 14, 20, 0));
        return review;
    }

    private static Page<OrderReview> pageOf(OrderReview... records) {
        Page<OrderReview> page = new Page<>(1, 10);
        page.setRecords(List.of(records));
        page.setTotal(records.length);
        page.setPages(1);
        return page;
    }

    /** 抓评价巡查查询的 SQL 片段 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private String capturedSql() {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderReviewMapper, times(1)).selectPage(any(), captor.capture());
        return captor.getValue().getSqlSegment();
    }

    /** WHERE 段：从整段 SQL 里截出 ORDER BY 之前的部分 */
    private String whereClause() {
        String sql = capturedSql();
        int orderByIdx = sql.indexOf("ORDER BY");
        return orderByIdx < 0 ? sql : sql.substring(0, orderByIdx).trim();
    }

    /** 查询参数（列名由 MP 生成，只认值） */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<Object, Object> queryParams() {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderReviewMapper, times(1)).selectPage(any(), captor.capture());
        // ⚠️ paramNameValuePairs 是**惰性填充**的：只在 getSqlSegment() 生成 SQL 时才写入。
        //    不先触发生成，拿到的就是一张空表。
        captor.getValue().getSqlSegment();
        return captor.getValue().getParamNameValuePairs();
    }
}
