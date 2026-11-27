package org.company.nianglin.controller.execution;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.Result;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.CheckinCreateDTO;
import org.company.nianglin.dto.LocationReportDTO;
import org.company.nianglin.dto.LocationSharingDTO;
import org.company.nianglin.dto.VitalSignCreateDTO;
import org.company.nianglin.dto.VitalSignQuery;
import org.company.nianglin.service.ExecutionService;
import org.company.nianglin.service.VitalSignService;
import org.company.nianglin.vo.CheckinResultVO;
import org.company.nianglin.vo.CheckinVO;
import org.company.nianglin.vo.FileUploadVO;
import org.company.nianglin.vo.LocationVO;
import org.company.nianglin.vo.ProgressVO;
import org.company.nianglin.vo.TrackPointVO;
import org.company.nianglin.vo.VitalSignVO;
import org.company.nianglin.vo.VitalWithdrawVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 陪诊执行接口。
 *
 * <p>对应 {@code docs/api/04-companion-execution.md} §1 ~ §5、§7 ~ §9。
 * 其中 §7～§9（位置上报 / 快照 / 共享开关）与地图可视化见 ADR-0022。</p>
 *
 * <h3>为什么 5 个查询接口不加 {@code @PreAuthorize}</h3>
 *
 * <p>「相关方」横跨四个角色 —— 下单家属、接单陪诊员、就诊老人本人、管理员，
 * 用注解要写成四个 {@code hasAnyRole}，而且<b>一个角色都挡不住越权</b>：
 * 家属 A 与家属 B 的角色完全相同。归属判定只能落在
 * {@code OrderService#requireInvolved}，注解加在这里是虚假的安全感。</p>
 *
 * <p>其中 {@code GET /location} <b>比其余四个更严</b>：实时位置只给本单家属与陪诊员，
 * 老人与管理员一律 {@code 3004}（ADR-0022 D10）。这条判定同样在 Service 层
 * （{@code requireLocationVisible}），因为它是「是不是这一单的家属」，不是「是不是家属」。</p>
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
    private final VitalSignService vitalSignService;

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

    /* ==================== 服务中代录体征（V4 · P2-2） ==================== */

    @Operation(summary = "按订单查询已录入的体征",
            description = "须为本单陪诊员（否则 3004），订单须为「服务中 / 已完成 / 已评价」"
                    + "（否则 3002），订单不存在返回 3001。只返回本订单关联的代录记录，"
                    + "按测量时间倒序分页；刷新页面后据此回显")
    @PreAuthorize("hasRole('" + RoleConstants.COMPANION + "')")
    @GetMapping("/{orderId}/vitals")
    public Result<PageResult<VitalSignVO>> listVitals(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("orderId") Long orderId,
            @Valid VitalSignQuery query) {
        return Result.success(vitalSignService.listForCompanion(orderId, query));
    }

    @Operation(summary = "服务中代录体征",
            description = "须为本单陪诊员（否则 3004），订单须为「服务中」（否则 3002），"
                    + "订单不存在返回 3001。只保存测量数值，老人取订单关联档案；"
                    + "同一老人同一类型同一分钟只允许一条（否则 2008）")
    @PreAuthorize("hasRole('" + RoleConstants.COMPANION + "')")
    @PostMapping("/{orderId}/vitals")
    public Result<VitalSignVO> createVital(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("orderId") Long orderId,
            @Valid @RequestBody VitalSignCreateDTO dto) {
        return Result.success("记录已保存", vitalSignService.createForCompanion(orderId, dto));
    }

    @Operation(summary = "撤回服务中代录的体征",
            description = "须为本单陪诊员且订单仍在「服务中」（否则 3004 / 3002）；"
                    + "记录不存在返回 2009，不属于本单返回 3004，非本人代录返回 2010")
    @PreAuthorize("hasRole('" + RoleConstants.COMPANION + "')")
    @DeleteMapping("/{orderId}/vitals/{recordId}")
    public Result<VitalWithdrawVO> withdrawVital(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("orderId") Long orderId,
            @Parameter(description = "体征记录 ID", example = "123")
            @PathVariable("recordId") Long recordId) {
        return Result.success("记录已撤回", vitalSignService.withdrawByCompanion(orderId, recordId));
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

    /* ==================== 实时位置（ADR-0022） ==================== */

    @Operation(summary = "上报当前位置",
            description = "须为本单陪诊员（否则 4003），订单须为「已接单 / 服务中」（否则 3002）。"
                    + "坐标一律 **WGS84**（浏览器 geolocation 原生值），服务端不做坐标转换；"
                    + "百度地图要 BD09 时由前端渲染前转换。"
                    + "同一订单两次上报间隔小于阈值返回 4004；位置共享已暂停返回 4005。"
                    + "每次上报都刷新最新点，companion_track 按抽稀规则写入；"
                    + "成功后向该订单推送 LOCATION 事件（**只发家属与陪诊员**）")
    @PreAuthorize("hasRole('" + RoleConstants.COMPANION + "')")
    @PostMapping("/{orderId}/location")
    public Result<LocationVO> reportLocation(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("orderId") Long orderId,
            @Valid @RequestBody LocationReportDTO dto) {
        return Result.success("位置已更新", executionService.reportLocation(orderId, dto));
    }

    @Operation(summary = "最新位置快照",
            description = "**仅本单家属与陪诊员可见**（老人 / 管理员返回 3004，与 LOCATION 推送口径一致）。"
                    + "进页面先拉一次铺底，之后靠 WebSocket 的 LOCATION 事件增量更新。"
                    + "从未上报过的订单返回 200 + 坐标字段为 null，前端按「尚未开始共享位置」渲染，"
                    + "不要当成错误。distanceMeters 为陪诊员到医院坐标的直线距离（与打卡 4001 同口径），"
                    + "**不是**到老人住址的距离")
    @GetMapping("/{orderId}/location")
    public Result<LocationVO> latestLocation(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("orderId") Long orderId) {
        return Result.success(executionService.latestLocation(orderId));
    }

    @Operation(summary = "开关位置共享",
            description = "须为本单陪诊员（否则 4003），订单须为「已接单 / 服务中」（否则 3002）。"
                    + "关闭后停止对外暴露实时位置，并推送 LOCATION_PAUSED 事件让家属端显示"
                    + "「已暂停共享」而不是一个冻住的点。**暂停不影响打卡**："
                    + "共享只决定「是否给家属看实时位置」，不决定「是否记录履约证据」")
    @PreAuthorize("hasRole('" + RoleConstants.COMPANION + "')")
    @PutMapping("/{orderId}/location-sharing")
    public Result<LocationVO> setLocationSharing(
            @Parameter(description = "订单 ID", example = "1001") @PathVariable("orderId") Long orderId,
            @Valid @RequestBody LocationSharingDTO dto) {
        LocationVO view = executionService.setLocationSharing(orderId, dto);
        return Result.success(Boolean.TRUE.equals(view.getSharingEnabled())
                ? "已恢复位置共享" : "已暂停位置共享", view);
    }
}
