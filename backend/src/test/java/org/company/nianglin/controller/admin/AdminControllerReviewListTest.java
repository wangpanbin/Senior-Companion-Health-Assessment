package org.company.nianglin.controller.admin;

import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.Result;
import org.company.nianglin.dto.AdminReviewQuery;
import org.company.nianglin.service.AdminService;
import org.company.nianglin.service.FeedbackService;
import org.company.nianglin.service.ReviewService;
import org.company.nianglin.service.SettlementService;
import org.company.nianglin.vo.AdminReviewVO;
import org.company.nianglin.vo.ReviewRulingResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 管理端评价巡查 Controller 契约测试（E4）。
 *
 * <p>覆盖三件事：</p>
 *
 * <ol>
 *   <li><b>委托</b> —— 列表端点把查询条件原样交给 {@code AdminService}，
 *       控制器不自己拼 SQL（筛选逻辑只允许有一份实现）；</li>
 *   <li><b>响应包装</b> —— 统一用 {@code Result.success} 包装，不自己造响应体；</li>
 *   <li><b>不越权调用别的服务</b> —— 列表是只读操作，
 *       不该顺手触发 Review 模块的写路径（裁定）。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since E4
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("管理端评价巡查 Controller")
class AdminControllerReviewListTest {

    @Mock
    private AdminService adminService;

    @Mock
    private ReviewService reviewService;

    @Mock
    private SettlementService settlementService;

    /** V6 产品反馈：本类只验评价巡查列表，反馈服务只需一个可注入的空实现 */
    @Mock
    private FeedbackService feedbackService;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(adminService, reviewService, settlementService, feedbackService);
    }

    @Test
    @DisplayName("评价巡查列表直接委托 AdminService，不自己拼筛选")
    void reviewListShouldDelegateToAdminService() {
        AdminReviewQuery query = new AdminReviewQuery();
        query.setCompanionId(307L);
        query.setIsValid(Boolean.FALSE);
        AdminReviewVO item = AdminReviewVO.of(review(), "张伟", "黄海涛", List.of("准时"));
        PageResult<AdminReviewVO> expected = new PageResult<>(1L, 1L, 10L, 1L, List.of(item));
        given(adminService.reviewList(any())).willReturn(expected);

        Result<PageResult<AdminReviewVO>> result = controller.reviewList(query);

        assertEquals(200, result.getCode());
        assertSame(expected, result.getData());
        // 查询条件必须原样传下去，不能在控制器里被改写
        verify(adminService).reviewList(query);
    }

    @Test
    @DisplayName("评价巡查是只读操作，不触碰 Review 模块的写路径")
    void reviewListShouldNotTouchReviewService() {
        given(adminService.reviewList(any())).willReturn(PageResult.empty(1L, 10L));

        controller.reviewList(new AdminReviewQuery());

        // 裁定（reviewService.reviewValidity）是写操作，列表页绝不能顺手调它
        verifyNoInteractions(reviewService);
    }

    private static org.company.nianglin.entity.OrderReview review() {
        org.company.nianglin.entity.OrderReview review = new org.company.nianglin.entity.OrderReview();
        review.setId(30001L);
        review.setOrderId(1031L);
        review.setOrderNo("NL20260820000031");
        review.setFamilyId(101L);
        review.setCompanionId(307L);
        review.setScore(5);
        review.setIsAnonymous(0);
        review.setIsValid(0);
        review.setCreateTime(LocalDateTime.of(2026, 9, 14, 20, 0));
        return review;
    }
}
