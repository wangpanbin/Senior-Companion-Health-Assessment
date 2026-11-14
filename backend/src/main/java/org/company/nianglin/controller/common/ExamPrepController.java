package org.company.nianglin.controller.common;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.company.nianglin.common.Result;
import org.company.nianglin.service.ExamPrepService;
import org.company.nianglin.vo.ExamPrepItemVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 检查准备须知查询（V5 · B）。
 *
 * <p>对应 {@code docs/api/14-exam-prep.md §1}。静态字典、已登录即可查、无参数；
 * 内容为医院公开须知的静态转述，每条 remark 自带来源与免责声明。</p>
 *
 * @author 银龄伴诊团队
 * @since V5
 */
@Tag(name = "检查准备")
@RestController
@RequestMapping("/api/exam-prep")
@RequiredArgsConstructor
public class ExamPrepController {

    private final ExamPrepService examPrepService;

    @Operation(summary = "检查准备须知查询",
            description = "返回启用的 EXAM_PREP 静态字典（docs/api/14-exam-prep.md §1）。"
                    + "就医须知转述，每条含来源与免责声明，不构成医疗建议，内容不由 LLM 生成（V5 · B）")
    @GetMapping
    public Result<List<ExamPrepItemVO>> list() {
        return Result.success(examPrepService.list());
    }
}
