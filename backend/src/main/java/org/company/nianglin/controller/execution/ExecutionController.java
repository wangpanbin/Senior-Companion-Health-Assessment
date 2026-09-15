package org.company.nianglin.controller.execution;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.CheckinCreateDTO;
import org.company.nianglin.service.ExecutionService;
import org.company.nianglin.vo.CheckinResultVO;
import org.company.nianglin.vo.CheckinVO;
import org.company.nianglin.vo.FileUploadVO;
import org.company.nianglin.vo.ProgressVO;
import org.company.nianglin.vo.TrackPointVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 陪诊执行接口。
 *
 * <p>对应 {@code docs/api/04-companion-execution.md} §1 ~ §5。</p>
 *
 * <h3>为什么 4 个查询接口不加 {@code @PreAuthorize}</h3>
 *
 * <p>「相关方」横跨四个角色 —— 下单家属、接单陪诊员、就诊老人本人、管理员，
 * 用注解要写成四个 {@code hasAnyRole}，而且<b>一个角色都挡不住越权</b>：
 * 家属 A 与家属 B 的角色完全相同。归属判定只能落在
 * {@code OrderService#requireInvolved}，注解加在这里是虚假的安全感。</p>
 *
 * <h3>老人账号（ELDER）不需要额外处理</h3>
 *
 * <p>老人在本模块只有读操作（看进度），写操作（打卡 / 上传照片）本就只属于陪诊员。
 * 「ELDER 写操作一律 403」由 {@code ElderReadOnlyInterceptor} 全局兜底，
 * 因此这里不出现 {@code @AllowElderWrite}。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@Slf4j
@RestController
@RequestMapping("/api/execution")
@RequiredArgsConstructor
@Tag(name = "04-陪诊执行", description = "打卡、轨迹与实时进度")
public class ExecutionController {

    private final ExecutionService executionService;

    /* ==================== 陪诊员操作 ==================== */

    @Operation(summary = "打卡",
            description = "须为本单陪诊员（否则 4003），订单须为「已接单 / 服务中」（否则 3002）。"
                    + "同一节点重复提交返回 4002；节点顺序回退返回 400；"
                    + "与订单地址距离超过阈值返回 4001。成功后同时写入打卡记录与轨迹点，"
                    + "并向该订单的订阅者推送实时进度")
    @PreAuthorize("hasRole('" + RoleConstants.COMPANION + "')")
    @PostMapping("/{orderId}/checkin")
    public Result<CheckinResultVO> checkin(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("orderId") Long orderId,
            @Valid @RequestBody CheckinCreateDTO dto) {
        return Result.success("打卡成功", executionService.checkin(orderId, dto));
    }

    @Operation(summary = "上传现场照片 / 取药凭证",
            description = "须为本单陪诊员（否则 4003）。仅接受 jpg / png / webp / pdf，"
                    + "文件类型按文件头（magic bytes）判定，不信任前端传来的 Content-Type；"
                    + "单文件上限 10 MB")
    @PreAuthorize("hasRole('" + RoleConstants.COMPANION + "')")
    @PostMapping("/{orderId}/photo")
    public Result<FileUploadVO> uploadPhoto(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("orderId") Long orderId,
            @Parameter(description = "文件", required = true) @RequestParam("file") MultipartFile file) {
        return Result.success("上传成功", executionService.uploadPhoto(orderId, file));
    }

    /* ==================== 相关方查询 ==================== */

    @Operation(summary = "打卡记录列表",
            description = "须为订单相关方（否则 3004）。按打卡时间升序返回，与前端时间线渲染顺序一致")
    @GetMapping("/{orderId}/checkins")
    public Result<List<CheckinVO>> checkins(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("orderId") Long orderId) {
        return Result.success(executionService.checkins(orderId));
    }

    @Operation(summary = "轨迹点列表",
            description = "须为订单相关方。一期不渲染地图，数据用于路径文字摘要与异常打卡取证")
    @GetMapping("/{orderId}/track")
    public Result<List<TrackPointVO>> track(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("orderId") Long orderId) {
        return Result.success(executionService.track(orderId));
    }

    @Operation(summary = "进度快照",
            description = "须为订单相关方。家属端进页面先拉一次铺底，之后靠 WebSocket 增量更新，"
                    + "避免页面空白")
    @GetMapping("/{orderId}/progress")
    public Result<ProgressVO> progress(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("orderId") Long orderId) {
        return Result.success(executionService.progress(orderId));
    }
}
