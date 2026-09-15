package org.company.nianglin.security;

import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 老人账号只读拦截器单测。
 *
 * <p>这是「老人账号只读」这条产品规则的<b>唯一执行点</b>。它一旦失效，
 * 老人账号就能调用所有写接口 —— 而前端隐藏按钮不构成任何安全边界，
 * 所以这里的每一条用例都是回归红线。</p>
 *
 * <p>采用纯单测（不起 Spring 容器）而非 MockMvc：拦截器的判断逻辑只依赖
 * {@code HttpServletRequest} 的方法名与 {@code HandlerMethod} 上的注解，
 * 手工构造即可完全覆盖，且执行快、无副作用。</p>
 *
 * @author 银龄伴诊团队
 */
@DisplayName("老人账号只读拦截")
class ElderReadOnlyInterceptorTest {

    private final ElderReadOnlyInterceptor interceptor = new ElderReadOnlyInterceptor();

    private HandlerMethod plainWriteHandler;
    private HandlerMethod allowedWriteHandler;
    private HandlerMethod classLevelAllowedHandler;
    private HandlerMethod readHandler;

    @BeforeEach
    void setUp() throws NoSuchMethodException {
        plainWriteHandler = handlerMethod("plainWrite");
        allowedWriteHandler = handlerMethod("allowedWrite");
        classLevelAllowedHandler = handlerMethodFromClass();
        readHandler = handlerMethod("plainRead");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("老人账号 GET 请求：放行")
    void elderGetShouldPass() {
        loginAs(RoleConstants.ELDER);
        assertTrue(interceptor.preHandle(request("GET"), new MockHttpServletResponse(), readHandler));
    }

    @Test
    @DisplayName("老人账号 POST 写请求：拦截，提示只读模式")
    void elderPostShouldBeRejected() {
        loginAs(RoleConstants.ELDER);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request("POST"), new MockHttpServletResponse(), plainWriteHandler));

        assertEquals(ResultCode.FORBIDDEN.getCode(), ex.getCode());
        assertTrue(ex.getMessage().contains("只读"), "提示语应说明老人账号为只读，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("老人账号 PUT / DELETE 同样被拦截（不能只防 POST）")
    void elderPutAndDeleteShouldBeRejected() {
        loginAs(RoleConstants.ELDER);

        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request("PUT"), new MockHttpServletResponse(), plainWriteHandler));
        assertThrows(BusinessException.class,
                () -> interceptor.preHandle(request("DELETE"), new MockHttpServletResponse(), plainWriteHandler));
    }

    @Test
    @DisplayName("@AllowElderWrite 标注的接口：老人账号放行（登出 / 改密必须由本人完成）")
    void annotatedWriteShouldPassForElder() {
        loginAs(RoleConstants.ELDER);
        assertTrue(interceptor.preHandle(request("POST"), new MockHttpServletResponse(), allowedWriteHandler));
    }

    @Test
    @DisplayName("类级 @AllowElderWrite 同样生效")
    void classLevelAnnotationShouldPassForElder() {
        loginAs(RoleConstants.ELDER);
        assertTrue(interceptor.preHandle(request("POST"), new MockHttpServletResponse(), classLevelAllowedHandler));
    }

    @Test
    @DisplayName("非老人账号的写请求：放行，交由各自的 @PreAuthorize 判断")
    void nonElderWriteShouldPass() {
        for (String role : new String[]{RoleConstants.FAMILY, RoleConstants.COMPANION, RoleConstants.ADMIN}) {
            loginAs(role);
            assertTrue(interceptor.preHandle(request("POST"), new MockHttpServletResponse(), plainWriteHandler),
                    role + " 的写请求不应被只读拦截器拦下");
        }
    }

    @Test
    @DisplayName("未登录的写请求：放行，由 Spring Security 返回 401 而不是 403")
    void anonymousWriteShouldPass() {
        // 不清 SecurityContext，保持匿名
        assertTrue(interceptor.preHandle(request("POST"), new MockHttpServletResponse(), plainWriteHandler));
    }

    /* ================================================================== */
    /* 测试夹具                                                            */
    /* ================================================================== */

    private MockHttpServletRequest request(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI("/api/order");
        return request;
    }

    private void loginAs(String role) {
        LoginUser loginUser = new LoginUser(1L, "someone", role, 0, "jti", System.currentTimeMillis() + 60000);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, loginUser.authorities()));
    }

    private HandlerMethod handlerMethod(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = ProbeHandler.class.getDeclaredMethod(name, parameterTypes);
        return new HandlerMethod(new ProbeHandler(), method);
    }

    private HandlerMethod handlerMethodFromClass() throws NoSuchMethodException {
        Method method = ClassLevelAllowedHandler.class.getDeclaredMethod("write");
        return new HandlerMethod(new ClassLevelAllowedHandler(), method);
    }

    /** 探针：模拟业务控制器 */
    @RestController
    static class ProbeHandler {

        @GetMapping("/read")
        public void plainRead() {
        }

        @PostMapping("/write")
        public void plainWrite() {
        }

        /** 模拟登出 / 改密这类「必须由本人完成」的写接口 */
        @AllowElderWrite("测试用：登出 / 改密")
        @PostMapping("/allowed-write")
        public void allowedWrite() {
        }
    }

    /** 探针：类级放行的控制器 */
    @AllowElderWrite("测试用：整类放行")
    @RestController
    static class ClassLevelAllowedHandler {

        @PostMapping("/write")
        public void write() {
        }
    }
}
