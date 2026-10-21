package org.company.nianglin.controller.assistant;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.company.nianglin.dto.AssistantChatDTO;
import org.company.nianglin.service.AssistantService;
import org.company.nianglin.vo.AssistantChatVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 家属智能助手（V3.7 · M14）。
 *
 * <h3>权限：仅 FAMILY</h3>
 *
 * <p>{@code hasRole('FAMILY')} 是<b>服务端</b>拦截，不依赖前端隐藏入口。
 * 老人账号（ELDER）按项目规则只读，本模块<b>完全不给老人开放</b> ——
 * 家属代问的工具不该成为老人自己的入口。</p>
 *
 * <h3>路径为什么是 {@code /api/assistant} 而不是 {@code /api/chat}</h3>
 *
 * <p>{@code /api/chat/**} 已被 M13 订单聊天占用（ADR-0011），
 * 那是「家属 ↔ 陪诊员」的订单内聊天，与本模块不是一回事。
 * 名字必须分开，否则后面维护的人会把两套东西混在一起。</p>
 *
 * <h3>与 AI 使用边界的关系</h3>
 *
 * <p>本端点只做「平台服务规则问答」与「解释规则引擎算好的推荐结果」，
 * 两件事都不引入外部医学知识。诊断 / 用药建议一律在 Python 侧被拒答
 * （见 {@code ai-service/app/guard.py} 与 ADR-0020 决策 1）。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.7
 */
@Slf4j
@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
@Tag(name = "13-家属智能助手", description = "家属端问答与陪诊员推荐解释（V3.7 · M14）")
public class AssistantController {

    private final AssistantService assistantService;

    @Operation(summary = "智能助手问答",
            description = """
                    家属提问，助手回答平台服务规则类问题，或解释系统已算好的候选陪诊员。

                    ⚠️ 能力边界：只答平台规则，**不做诊断、不开药方**；
                    医疗类提问一律返回固定拒答话术并引导线下就医。

                    ⚠️ 推荐 ≠ 派单：候选由 V3.4 规则引擎排序，接单权始终在陪诊员本人。
                    """)
    @PreAuthorize("hasRole('FAMILY')")
    @PostMapping("/chat")
    public Result<AssistantChatVO> chat(@Valid @RequestBody AssistantChatDTO dto) {
        return Result.success(assistantService.chat(dto));
    }
}
