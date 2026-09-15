package org.company.nianglin.controller.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.company.nianglin.dto.ChangePasswordDTO;
import org.company.nianglin.dto.LoginDTO;
import org.company.nianglin.dto.LogoutDTO;
import org.company.nianglin.dto.RefreshTokenDTO;
import org.company.nianglin.dto.RegisterDTO;
import org.company.nianglin.security.AllowElderWrite;
import org.company.nianglin.service.AuthService;
import org.company.nianglin.service.CaptchaService;
import org.company.nianglin.util.WebUtil;
import org.company.nianglin.vo.CaptchaVO;
import org.company.nianglin.vo.LoginVO;
import org.company.nianglin.vo.RegisterVO;
import org.company.nianglin.vo.TokenVO;
import org.company.nianglin.vo.UserInfoVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证与账号接口。
 *
 * <p>对应文档：{@code docs/api/01-auth-user.md}（覆盖模块 M2）。</p>
 *
 * <p>其中 {@code /captcha}、{@code /register}、{@code /login}、{@code /refresh}
 * 在 {@code SecurityConfig} 的公开白名单里；{@code /logout}、{@code /me}、
 * {@code /password} 需要登录，由 {@code anyRequest().authenticated()} 兜底。</p>
 *
 * @author 银龄伴诊团队
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "01-认证与账号", description = "验证码、注册、登录、刷新令牌、登出、当前用户、修改密码")
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;

    @Operation(summary = "获取图形验证码", description = "公开接口。返回 captchaKey 与 base64 图片，有效期 300 秒，校验后立即失效")
    @GetMapping("/captcha")
    public Result<CaptchaVO> captcha() {
        return Result.success(captchaService.generate());
    }

    @Operation(summary = "注册", description = "公开接口。角色只能为 ELDER / FAMILY / COMPANION，注册为 ADMIN 一律拒绝")
    @PostMapping("/register")
    public Result<RegisterVO> register(@Valid @RequestBody RegisterDTO dto) {
        return Result.success("注册成功", new RegisterVO(authService.register(dto)));
    }

    @Operation(summary = "登录", description = "公开接口。账号不存在与密码错误返回同一个错误码 1001，防止账号枚举")
    @PostMapping("/login")
    public Result<LoginVO> login(@Valid @RequestBody LoginDTO dto, HttpServletRequest request) {
        return Result.success("登录成功", authService.login(dto, WebUtil.clientInfo(request)));
    }

    @Operation(summary = "刷新访问令牌", description = "公开接口。凭 refreshToken 换新的 accessToken；本接口自身不能再触发刷新，否则会死循环")
    @PostMapping("/refresh")
    public Result<TokenVO> refresh(@Valid @RequestBody RefreshTokenDTO dto, HttpServletRequest request) {
        return Result.success("刷新成功", authService.refresh(dto.getRefreshToken(), WebUtil.clientInfo(request)));
    }

    @Operation(summary = "登出", description = "把当前访问令牌加入 Redis 黑名单；请求体可选，传入 refreshToken 则一并作废")
    @AllowElderWrite("登出必须由本人完成，否则老人账号登录后将无法退出")
    @PostMapping("/logout")
    public Result<Void> logout(@RequestBody(required = false) LogoutDTO dto) {
        authService.logout(dto == null ? null : dto.getRefreshToken());
        return Result.<Void>success("已退出登录", null);
    }

    @Operation(summary = "获取当前用户信息", description = "刷新页面后恢复登录态用；返回的手机号已脱敏")
    @GetMapping("/me")
    public Result<UserInfoVO> me() {
        return Result.success(authService.currentUser());
    }

    @Operation(summary = "修改密码", description = "成功后该用户所有已签发令牌立即失效，需重新登录")
    @AllowElderWrite("修改自己的密码必须由本人完成")
    @PutMapping("/password")
    public Result<Void> changePassword(@Valid @RequestBody ChangePasswordDTO dto) {
        authService.changePassword(dto);
        return Result.<Void>success("密码修改成功，请重新登录", null);
    }
}
