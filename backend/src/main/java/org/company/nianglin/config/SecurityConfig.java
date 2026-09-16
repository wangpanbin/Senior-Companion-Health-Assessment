package org.company.nianglin.config;

import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.company.nianglin.security.JwtAuthenticationFilter;
import org.company.nianglin.security.RestAccessDeniedHandler;
import org.company.nianglin.security.RestAuthenticationEntryPoint;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置（M2 已收紧）。
 *
 * <p><b>鉴权分三层，各管一件事</b>：</p>
 * <ol>
 *   <li><b>认证</b>：{@link JwtAuthenticationFilter} 验签并写入 {@code SecurityContext}。
 *       它从不直接拒绝请求，只决定「这次请求有没有身份」。</li>
 *   <li><b>接口准入</b>：本类的 {@code authorizeHttpRequests} 决定哪些路径免登录，
 *       其余一律需要认证。</li>
 *   <li><b>角色与只读</b>：业务接口用 {@code @PreAuthorize("hasRole('FAMILY')")} 做方法级鉴权；
 *       「老人账号只读」这条产品规则由 {@code ElderReadOnlyInterceptor} 单独承担，
 *       因为它不是「角色对不对」的问题，{@code @PreAuthorize} 表达不了。</li>
 * </ol>
 *
 * <p>最终防线是资源归属校验（订单属于哪个家属、档案属于哪个老人），
 * 那属于各业务模块（M3–M9）的职责，光靠角色挡住不了一个家属去读别人的订单。</p>
 *
 * <h3>为什么必须放行 ASYNC / ERROR 分发</h3>
 *
 * <p>Spring Security 6 起 {@code AuthorizationFilter} 默认对<b>所有</b> {@code DispatcherType} 生效，
 * 而 {@link JwtAuthenticationFilter} 继承 {@code OncePerRequestFilter} 且未覆写
 * {@code shouldNotFilterErrorDispatch()}，因此<b>不会</b>在 ERROR 分发里重新解析令牌。</p>
 *
 * <p>两条规则一叠加，SSE 长连接的断开就变成一场假故障（实测于 {@code GET /sse/message}）：
 * 客户端关页面 → 写通道失败 → 容器把这次请求以 ERROR 分发重跑一遍 →
 * 这一轮没有 {@code SecurityContext}（认证过滤器被跳过）→
 * {@code AuthorizationFilter} 抛 {@code AccessDeniedException} →
 * 此时响应早已 committed，{@code ExceptionTranslationFilter} 连 403 都写不进去 →
 * Tomcat 再记一条 ERROR。结果是「用户关了个浏览器」被记成两条服务端异常，
 * 真正的故障淹在噪声里。</p>
 *
 * <p>放行这两类分发<b>不构成绕过</b>：{@code DispatcherType} 由容器设置、客户端无法伪造；
 * 而且首次 REQUEST 分发仍照常鉴权 —— 能产生 ASYNC / ERROR 分发的前提，
 * 是那个请求本身已经通过了 REQUEST 分发的检查。</p>
 *
 * @author 银龄伴诊团队
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * CORS 放行来源，与 {@code WebMvcConfig} 读的是<b>同一个属性</b>。
     *
     * <p>⚠️ 这里才是**真正生效**的那一份：{@code filterChain} 用
     * {@code .cors(cors -> cors.configurationSource(corsConfigurationSource()))}
     * 把 Security 自己的 {@link CorsConfigurationSource} 交给了 CORS 过滤器，
     * 于是 {@code WebMvcConfig#addCorsMappings} 对经过安全链的请求<b>不起作用</b>。
     * 以前这个值被硬编码在这里，改 {@code WebMvcConfig} 或改配置文件都看不出效果，
     * 排查时极易被带偏（曾据此误判成「vite 代理没转发 WebSocket」）。</p>
     */
    @Value("${nianglin.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String[] allowedOrigins;

    /** 无需登录即可访问的白名单 */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/health",
            "/api/auth/captcha",
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
            // WebSocket 升级请求：令牌走 query 参数（浏览器 WebSocket 不允许自定义请求头），
            // 由 websocket/JwtHandshakeInterceptor 在握手阶段校验 JWT 与订单归属。
            // 放行到 Security 之外是必须的 —— 否则过滤器链会因为「没有 Authorization 头」
            // 直接把升级请求判成未认证，握手永远到不了拦截器
            "/ws/**",
            // 上传文件的静态访问（打卡照片、投诉证据）
            "/uploads/**",
            // 接口文档相关（dev 环境；prod 由 knife4j.enable=false 关闭）
            "/doc.html",
            "/webjars/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/favicon.ico",
            "/error"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;

    /**
     * 密码编码器：BCrypt。
     *
     * <p>合规红线：数据库中绝不允许出现明文密码，校验值必须以 {@code $2a$} 开头。</p>
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 阻止 {@link JwtAuthenticationFilter} 被 Servlet 容器二次注册。
     *
     * <p>它是 {@code @Component}，Spring Boot 会把所有 {@code Filter} 类型的 bean
     * 自动注册到 Servlet 容器（映射 {@code /*}）。那样一来每个请求会<b>被解析两遍令牌</b>：
     * 先由 Spring Security 链内的实例处理，安全链退出时 {@code SecurityContextHolder} 被清空，
     * 再由容器实例重新解析一次。结果虽然正确，但纯属浪费，也容易让后来者误判执行顺序。</p>
     *
     * <p>显式禁用注册后，它的唯一入口就是 {@code addFilterBefore}。</p>
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtFilterRegistration(JwtAuthenticationFilter filter) {
        FilterRegistrationBean<JwtAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 前后端分离 + JWT，不使用 Session，因此关闭 CSRF（没有 Cookie 就无从 CSRF）
                .csrf(csrf -> csrf.disable())
                // 使用下面自定义的 CORS 规则
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // 无状态：不创建 HttpSession
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 前后端分离，关闭默认登录页、HTTP Basic 与登出过滤器
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                // 认证 / 授权失败也要返回统一响应结构，否则前端拿到空 401 或 HTML，无法触发刷新令牌
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        // ASYNC / ERROR 分发必须放行，否则 SSE 长连接的断开会在日志里刷 ERROR ——
                        // 完整推理见下方 javadoc「为什么必须放行 ASYNC / ERROR 分发」。
                        .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 其余全部需要登录。角色细粒度控制在各接口的 @PreAuthorize 上，
                        // 不在这里堆路径规则 —— 路径规则一多就必然有人漏配，而漏配的那条就是漏洞
                        .anyRequest().authenticated())
                // JWT 过滤器必须在用户名密码过滤器之前，否则先 401 再认证就晚了
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(Arrays.asList(allowedOrigins));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
