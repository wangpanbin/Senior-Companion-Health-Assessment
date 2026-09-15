package org.company.nianglin.controller.user;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.PageResult;
import org.company.nianglin.common.Result;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.dto.ElderBindDTO;
import org.company.nianglin.dto.ElderCreateDTO;
import org.company.nianglin.dto.ElderQuery;
import org.company.nianglin.dto.ElderUpdateDTO;
import org.company.nianglin.service.ElderService;
import org.company.nianglin.vo.ElderIdVO;
import org.company.nianglin.vo.ElderVO;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 老人档案接口。
 *
 * <p>对应 {@code docs/api/02-elder-family.md} §6 ~ §12。</p>
 *
 * <h3>这里的 {@code @PreAuthorize} 只做了一半的事</h3>
 *
 * <p>注解解决的是「<b>你是不是家属</b>」，解决不了「<b>这个老人是不是你家的</b>」。
 * 家属 A 拿自己的令牌请求 {@code /api/user/elder/402}（家属 B 的老人），
 * {@code hasRole('FAMILY')} 会放行，真正拦住他的是 Service 里的归属校验（返回 2006）。
 * 写新接口时不要只加注解就以为安全了。</p>
 *
 * <h3>为什么 {@code GET /{id}} 允许 ELDER 进来</h3>
 *
 * <p>老人账号虽然只读，但得能看到自己的档案（这是 GET，不被只读拦截器拦）。
 * 所以这里放行 ELDER，由 Service 判断「这条档案的 {@code user_id} 是不是他自己」，
 * 不是就 2006。放行一个角色 + 服务端做归属判断，比在这里堆路径规则可靠。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Slf4j
@RestController
@RequestMapping("/api/user/elder")
@RequiredArgsConstructor
@Tag(name = "02-用户与档案", description = "老人档案增删改查与家属绑定")
public class ElderController {

    private final ElderService elderService;

    /* ==================== 档案 CRUD ==================== */

    @Operation(summary = "我的老人档案列表",
            description = "只返回当前登录家属有效绑定的老人；列表页姓名与手机号脱敏，"
                    + "不返回身份证号、地址与病史")
    @PreAuthorize("hasRole('" + RoleConstants.FAMILY + "')")
    @GetMapping
    public Result<PageResult<ElderVO>> page(@Valid ElderQuery query) {
        return Result.success(elderService.page(query));
    }

    @Operation(summary = "新增老人档案",
            description = "家属代为建档（老人可以没有登录账号）。建档后自动建立绑定关系并标记为已绑定。"
                    + "不接受 age 字段，年龄由出生日期实时计算")
    @PreAuthorize("hasRole('" + RoleConstants.FAMILY + "')")
    @PostMapping
    public Result<ElderIdVO> create(@Valid @RequestBody ElderCreateDTO dto) {
        return Result.success("添加成功", ElderIdVO.of(elderService.create(dto)));
    }

    @Operation(summary = "老人档案详情",
            description = "家属须为绑定人，管理员可读任意档案，老人只能读自己的档案。"
                    + "身份证号返回脱敏串，地址门牌号打码")
    @PreAuthorize("hasAnyRole('" + RoleConstants.FAMILY + "','"
            + RoleConstants.ADMIN + "','" + RoleConstants.ELDER + "')")
    @GetMapping("/{id}")
    public Result<ElderVO> detail(
            @Parameter(description = "老人档案 ID", example = "401") @PathVariable("id") Long id) {
        return Result.success(elderService.detail(id));
    }

    @Operation(summary = "修改老人档案",
            description = "局部更新：字段不传表示不修改，传空串表示清空该字段")
    @PreAuthorize("hasRole('" + RoleConstants.FAMILY + "')")
    @PutMapping("/{id}")
    public Result<Void> update(
            @Parameter(description = "老人档案 ID", example = "401") @PathVariable("id") Long id,
            @Valid @RequestBody ElderUpdateDTO dto) {
        elderService.update(id, dto);
        return Result.success("保存成功", null);
    }

    @Operation(summary = "删除老人档案",
            description = "逻辑删除，保留历史订单与用药记录的关联。"
                    + "存在进行中的订单时返回 409")
    @PreAuthorize("hasRole('" + RoleConstants.FAMILY + "')")
    @DeleteMapping("/{id}")
    public Result<Void> delete(
            @Parameter(description = "老人档案 ID", example = "401") @PathVariable("id") Long id) {
        elderService.delete(id);
        return Result.success("已删除", null);
    }

    /* ==================== 绑定 / 解绑 ==================== */

    @Operation(summary = "绑定老人账号",
            description = "凭老人注册手机号认领已有账号。一期只支持 bindType=PHONE；"
                    + "INVITE_CODE 返回 501。一个老人只允许被一位主要家属绑定")
    @PreAuthorize("hasRole('" + RoleConstants.FAMILY + "')")
    @PostMapping("/bind")
    public Result<ElderIdVO> bind(@Valid @RequestBody ElderBindDTO dto) {
        return Result.success("绑定成功", ElderIdVO.of(elderService.bind(dto)));
    }

    @Operation(summary = "解绑老人",
            description = "解除当前家属与老人的绑定关系。存在进行中的订单时返回 409")
    @PreAuthorize("hasRole('" + RoleConstants.FAMILY + "')")
    @DeleteMapping("/{id}/bind")
    public Result<Void> unbind(
            @Parameter(description = "老人档案 ID", example = "401") @PathVariable("id") Long id) {
        elderService.unbind(id);
        return Result.success("已解绑", null);
    }
}
