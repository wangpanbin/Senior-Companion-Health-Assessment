package org.company.nianglin.controller.review;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.Result;
import org.company.nianglin.dto.ComplaintCreateDTO;
import org.company.nianglin.dto.ComplaintQuery;
import org.company.nianglin.service.ComplaintService;
import org.company.nianglin.vo.ComplaintCreateResultVO;
import org.company.nianglin.vo.ComplaintVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投诉接口。
 *
 * <p>对应 {@code docs/api/06-review-complaint.md} §5 ~ §7。</p>
 *
 * <h3>三个接口都不写 {@code @PreAuthorize}</h3>
 *
 * <p>原因不是「忘了」：投诉的可见范围由<b>订单关系</b>决定 ——
 * 家属投诉陪诊员、陪诊员投诉家属，两者的角色集合是
 * {@code hasAnyRole('FAMILY','COMPANION')}，但注解只能判断角色、判断不了
 * 「这笔订单是不是你的」。既然 Service 层无论如何都要做归属判定，
 * 再叠一层角色注解只会让「403 与 3004 到底谁先返回」变得难以预测。</p>
 *
 * <p>ELDER 的角色拦截由 {@code ElderReadOnlyInterceptor} 兜底：
 * 老人账号调 POST 一律 403，不需要在本类里重复声明。</p>
 *
 * @author 银龄伴诊团队
 * @since M7
 */
@Slf4j
@RestController
@RequestMapping("/api/complaint")
@RequiredArgsConstructor
@Tag(name = "06-投诉", description = "投诉提交、列表与详情")
public class ComplaintController {

    private final ComplaintService complaintService;

    @Operation(summary = "提交投诉",
            description = "关联订单须为当前用户相关订单（否则 3004）。投诉人与被投诉人由订单关系"
                    + "自动推导、不接受前端传入；同一订单存在未结案投诉时返回 409；"
                    + "内容命中敏感词返回 6003")
    @PostMapping
    public Result<ComplaintCreateResultVO> create(@Valid @RequestBody ComplaintCreateDTO dto) {
        return Result.success("投诉已提交，我们会尽快处理", complaintService.create(dto));
    }

    @Operation(summary = "我的投诉列表",
            description = "不传 role 时返回「我投诉的 + 投诉我的」并集；"
                    + "无论 role 怎么传，可见范围都不会超出与当前用户相关的投诉")
    @GetMapping
    public Result<PageResult<ComplaintVO>> myList(@Valid ComplaintQuery query) {
        return Result.success(complaintService.myList(query));
    }

    @Operation(summary = "投诉详情",
            description = "仅投诉人 / 被投诉人 / 管理员可见，其他人 403；记录不存在为 6004。"
                    + "双方姓名均脱敏，处理结果仅在已结案 / 已驳回时返回")
    @GetMapping("/{id}")
    public Result<ComplaintVO> detail(
            @Parameter(description = "投诉 ID", example = "3001") @PathVariable("id") Long id) {
        return Result.success(complaintService.detail(id));
    }
}
