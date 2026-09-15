package org.company.nianglin.security;

import org.company.nianglin.common.ResultCode;
import org.company.nianglin.exception.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 登录态读取工具。
 *
 * <p>业务层要拿「当前是谁」时统一走本类，<b>不要</b>直接碰
 * {@code SecurityContextHolder}，也不要从请求参数里接收 userId ——
 * 后者等于把身份交给前端决定，是典型的越权漏洞来源。</p>
 *
 * @author 银龄伴诊团队
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /**
     * 取当前登录用户，未登录返回 {@code null}。
     *
     * <p>匿名请求（Spring Security 的 {@code AnonymousAuthenticationToken}）的 principal
     * 是字符串 {@code "anonymousUser"}，因此会落到 {@code null} 分支。</p>
     */
    public static LoginUser currentUserOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        return principal instanceof LoginUser loginUser ? loginUser : null;
    }

    /** 取当前登录用户，未登录抛 401 */
    public static LoginUser currentUser() {
        LoginUser loginUser = currentUserOrNull();
        if (loginUser == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return loginUser;
    }

    /** 当前登录用户 ID */
    public static Long currentUserId() {
        return currentUser().userId();
    }

    /** 当前登录用户角色枚举名 */
    public static String currentRole() {
        return currentUser().role();
    }

    /** 当前登录用户是否为老年患者（只读角色） */
    public static boolean isElder() {
        LoginUser loginUser = currentUserOrNull();
        return loginUser != null && loginUser.isElder();
    }
}
