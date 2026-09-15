package org.company.nianglin.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置。
 *
 * <p>开发阶段放开跨域，方便前端 5173 直连后端 8080（虽然 Vite 已配代理，双保险）。</p>
 *
 * <p>⚠️ 生产环境请把 {@code nianglin.cors.allowed-origins} 收紧为真实域名。</p>
 *
 * @author 银龄伴诊团队
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${nianglin.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Authorization")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
