package org.company.nianglin.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.exception.BusinessException;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * 老人账号只读拦截器。
 *
 * <p><b>为什么要有这一层</b>：{@code @PreAuthorize} 只能判断「角色对不对」，
 * 表达不了「这个角色只能读不能写」这条产品规则。若靠每个写接口自己写
 * {@code hasAnyRole('FAMILY','COMPANION','ADMIN')} 来绕过 ELDER，
 * 那么任何一次新接口的疏忽都会静默地把老人账号变成可写 —— 这是不可接受的。</p>
 *
 * <p>因此改为「默认拒绝」：只要是写方法且角色为 ELDER，一律 403，
 * 只有显式标注 {@link AllowElderWrite} 的接口才放行。新增接口<b>默认安全</b>。</p>
 *
 * <p>⚠️ 前端隐藏按钮只提升体验，不构成安全边界（{@code AGENT.md} §4.3）。</p>
 *
 * @author 银龄伴诊团队
 */
@Slf4j
@Component
public class ElderReadOnlyInterceptor implements HandlerInterceptor {

    /** 只读 HTTP 方法：这些方法不拦截 */
    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (READ_METHODS.contains(request.getMethod())) {
            return true;
        }

        LoginUser loginUser = SecurityUtils.currentUserOrNull();
        // 未登录留给 Security 的授权规则处理；非老人账号由各自的 @PreAuthorize 负责
        if (loginUser == null || !loginUser.isElder()) {
            return true;
        }

        if (handler instanceof HandlerMethod handlerMethod && isWriteAllowed(handlerMethod)) {
            return true;
        }

        log.warn("老人账号写操作被拦截 | {} {} | userId={}", request.getMethod(), request.getRequestURI(),
                loginUser.userId());
        throw new BusinessException(ResultCode.FORBIDDEN, "老人账号为只读模式，该操作请由家属代为完成");
    }

    private boolean isWriteAllowed(HandlerMethod handlerMethod) {
        return AnnotatedElementUtils.hasAnnotation(handlerMethod.getMethod(), AllowElderWrite.class)
                || AnnotatedElementUtils.hasAnnotation(handlerMethod.getBeanType(), AllowElderWrite.class);
    }
}
