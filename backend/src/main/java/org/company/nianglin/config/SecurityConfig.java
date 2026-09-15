package org.company.nianglin.config;

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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Spring Security 配置。
 *
 * <p><b>当前处于 M0 骨架阶段</b>：只搭好无状态会话 + BCrypt 密码器 + 放行规则，
 * 尚未接入 JWT 过滤器与 {@code @RequireRole} 注解 —— 那部分属于 <b>M2 认证与多角色鉴权</b>。</p>
 *
 * <p>⚠️ TODO(M2)：</p>
 * <ol>
 *   <li>新增 {@code JwtAuthenticationFilter}，插到 {@code UsernamePasswordAuthenticationFilter} 之前</li>
 *   <li>把 {@code anyRequest().permitAll()} 换成基于角色的细粒度规则</li>
 *   <li>{@code @EnableMethodSecurity} 已开启，业务层可直接用
 *       {@code @PreAuthorize("hasRole('FAMILY')")} 做接口级鉴权</li>
 *   <li>老人账号（ELDER）默认只读：写接口必须同时校验角色与「代操作」关系</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    /** 无需登录即可访问的白名单 */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/health",
            "/api/auth/captcha",
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            // 接口文档相关
            "/doc.html",
            "/webjars/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/v3/api-docs/**",
            "/favicon.ico",
            "/error"
    };

    /**
     * 密码编码器：BCrypt。
     *
     * <p>合规红线：数据库中绝不允许出现明文密码，校验值必须以 {@code $2a$} 开头。</p>
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 前后端分离 + JWT，不使用 Session，因此关闭 CSRF
                .csrf(csrf -> csrf.disable())
                // 使用下面自定义的 CORS 规则
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // 无状态：不创建 HttpSession
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 前后端分离，关闭默认登录页、HTTP Basic 与登出过滤器
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // TODO(M2)：此处收窄为按角色鉴权（ELDER 只读、COMPANION 需审核通过等）
                        .anyRequest().permitAll()
                );
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
