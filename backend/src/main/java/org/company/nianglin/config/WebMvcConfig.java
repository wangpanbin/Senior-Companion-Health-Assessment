package org.company.nianglin.config;

import lombok.RequiredArgsConstructor;
import org.company.nianglin.security.ElderReadOnlyInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
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
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    /** 只对业务接口生效，接口文档与静态资源不受影响 */
    private static final String API_PATH_PATTERN = "/api/**";

    private final ElderReadOnlyInterceptor elderReadOnlyInterceptor;

    @Value("${nianglin.cors.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}")
    private String[] allowedOrigins;

    @Value("${nianglin.file.upload-dir:./uploads}")
    private String uploadDir;

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

    /**
     * 上传文件的静态访问映射：{@code /uploads/**} → 磁盘上的 {@code nianglin.file.upload-dir}。
     *
     * <p>没有这条映射，上传接口会返回一个「看着像 URL、实际 404」的地址，
     * 前端图片全裂，而错误信息是 404 —— 排查方向完全被带偏。
     * {@code file:} 前缀表示「磁盘绝对/相对路径」，与 {@code classpath:} 区分开，
     * 确保读取的是运行时写入目录而不是打包进 jar 的静态资源。</p>
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + normalizeDir(uploadDir));
    }

    /** 保证目录以 {@code /} 结尾，否则 Spring 会把最后一段当成文件名拼出错误路径 */
    private static String normalizeDir(String dir) {
        if (dir == null || dir.isBlank()) {
            return "./uploads/";
        }
        String value = dir.trim().replace('\\', '/');
        return value.endsWith("/") ? value : value + "/";
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 老人账号只读：默认拒绝所有写方法，仅放行显式标注 @AllowElderWrite 的接口
        registry.addInterceptor(elderReadOnlyInterceptor).addPathPatterns(API_PATH_PATTERN);
    }
}
