package org.company.nianglin.config;

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
 * @author 银龄伴诊团队
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    /** 无需登录即可访问的白名单 */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/health",
            "/api/auth/captcha",
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
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
        config.setAllowedOriginPatterns(List.of("http://localhost:5173", "http://127.0.0.1:5173"));
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
