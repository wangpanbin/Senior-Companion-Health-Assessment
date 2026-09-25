package org.company.nianglin.controller.admin;

import org.company.nianglin.common.Result;
import org.company.nianglin.dto.ReviewRulingDTO;
import org.company.nianglin.service.AdminService;
import org.company.nianglin.service.ReviewService;
import org.company.nianglin.vo.ReviewRulingResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("管理端评价裁定 Controller")
class AdminControllerReviewTest {

    @Mock
    private AdminService adminService;

    @Mock
    private ReviewService reviewService;

    private AdminController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminController(adminService, reviewService);
    }

    @Test
    @DisplayName("评价裁定直接委托 Review interface")
    void reviewValidityShouldDelegateToReviewService() {
        ReviewRulingDTO dto = new ReviewRulingDTO();
        dto.setIsValid(false);
        dto.setReason("家属描述与打卡记录明显不符，证据充分，裁定为无效评价");
        ReviewRulingResultVO expected = ReviewRulingResultVO.of(30001L, false, 307L);
        given(reviewService.reviewValidity(30001L, dto)).willReturn(expected);

        Result<ReviewRulingResultVO> result = controller.reviewValidity(30001L, dto);

        assertEquals("裁定已生效", result.getMessage());
        assertSame(expected, result.getData());
        verify(reviewService).reviewValidity(30001L, dto);
        verifyNoInteractions(adminService);
    }
}
