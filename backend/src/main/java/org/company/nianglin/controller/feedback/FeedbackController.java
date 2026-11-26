package org.company.nianglin.controller.feedback;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.Result;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.FeedbackCreateDTO;
import org.company.nianglin.dto.FeedbackQuery;
import org.company.nianglin.service.FeedbackService;
import org.company.nianglin.vo.FeedbackCreateResultVO;
import org.company.nianglin.vo.FeedbackVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 产品反馈（客户端）接口。
 *
 * <p>对应 {@code docs/api/15-product-feedback.md} §1 ~ §3。</p>
 *
 * <h3>为什么角色注解只写 FAMILY / COMPANION</h3>
 *
 * <p>「老人账号只读」是项目硬规则，本模块<b>不开</b> {@code @AllowElderWrite} 豁免 ——
 * 反馈是给「能对产品提意见的使用者」用的，老人端交互刻意做减法，意见由家属代提。
 * 因此这里在注解层面就把 ELDER 挡掉，不依赖只读拦截器兜底（两道都留着更稳：
 * 注解给出语义，拦截器给出默认安全的底线）。</p>
 *
 * <h3>为什么三个接口都不写 {@code @AllowElderWrite}</h3>
 *
 * <p>补充说明同 {@code ChatController}：本控制器不产生「必须允许老人写」的场景。
 * 若将来要放开，应该先改产品口径（老人端是否该有反馈入口）再改这里的注解 ——
 * 而不是反过来。</p>
 *
 * @author 银龄伴诊团队
 * @since V6 · 产品反馈
 */
@Slf4j
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('" + RoleConstants.FAMILY + "','" + RoleConstants.COMPANION + "')")
@Tag(name = "15-产品反馈", description = "客户端提交产品建议与查看处理进度")
public class FeedbackController {

    private final FeedbackService feedbackService;

    @Operation(summary = "提交产品反馈",
            description = "分类 + 正文必填，截图最多 3 张且须先经 POST /api/file/upload（bizType=FEEDBACK）"
                    + "拿到 URL。提交人与角色取自登录态，不接受前端传入。"
                    + "正文命中敏感词返回 6003；**不过医疗红线词表**（用户提「用药页能不能提醒别吃错」"
                    + "是正当建议，套那套词表会被误杀）。提交时不通知管理员")
    @PostMapping
    public Result<FeedbackCreateResultVO> create(@Valid @RequestBody FeedbackCreateDTO dto) {
        return Result.success("反馈已提交，感谢您的建议", feedbackService.create(dto));
    }

    @Operation(summary = "我的反馈列表",
            description = "可见范围恒为本人提交的（submitterId 从令牌取，本接口没有该参数）；"
                    + "处理说明仅在终态（已采纳 / 未采纳）返回")
    @GetMapping("/mine")
    public Result<PageResult<FeedbackVO>> myList(@Valid FeedbackQuery query) {
        return Result.success(feedbackService.myList(query));
    }

    @Operation(summary = "反馈详情",
            description = "仅提交人本人或管理员可见；不存在与无权访问统一返回 12001 ——"
                    + "刻意不区分，避免按 ID 枚举他人反馈是否存在")
    // 方法级注解覆盖类级：管理员要在后台页面上点开某条反馈看原文与截图，
    // 但「能看」与「能提交 / 能看自己列表」是三件事。可见范围（本人 / ADMIN）
    // 由 Service 层判定，不靠角色注解 —— 注解只能表达「是不是管理员」，
    // 表达不了「这条反馈是不是你的」。
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}")
    public Result<FeedbackVO> detail(
            @Parameter(description = "反馈 ID", example = "5001") @PathVariable("id") Long id) {
        return Result.success(feedbackService.detail(id));
    }
}
