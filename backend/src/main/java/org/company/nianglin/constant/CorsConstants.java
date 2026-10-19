package org.company.nianglin.constant;

/**
 * CORS 相关常量。
 *
 * <p><b>为什么要单独抽出这个常量</b>：{@code SecurityConfig} 与 {@code WebMvcConfig}
 * 读的是同一个属性 {@code nianglin.cors.allowed-origins}，但历史上两处各写了一份
 * {@code @Value} 兜底串，且曾出现过不同步 —— 修复时只改了 {@code WebMvcConfig}、
 * 漏了 {@code SecurityConfig}，结果同一进程内 Spring Security 层放行 5173、
 * MVC 层放行 5141（见 {@code AGENTS.md} §2.10 记录）。</p>
 *
 * <p>把兜底串收敛到本常量后，两处引用同一个编译期常量，改端口时只改这里一处，
 * 结构性消除再次发散的可能。</p>
 *
 * <p>⚠️ 该兜底值仅在「既无配置文件、也无环境变量」时生效。生产 profile 会通过
 * {@code nianglin.cors.allowed-origins} 显式指定真实域名（见 {@code application-prod.yml}），
 * 正常情况下不会走到这里。</p>
 *
 * @author 银龄伴诊团队
 * @since 安全加固 2026-10
 */
public final class CorsConstants {

    /**
     * CORS 兜底白名单：本机 5141（前端 dev server 真实端口）。
     *
     * <p>必须与 {@code vite.config.js} 的 {@code VITE_PORT} 默认值、
     * {@code playwright.config.js} 的 {@code BASE_URL} 保持一致。</p>
     */
    public static final String DEFAULT_ALLOWED_ORIGINS = "http://localhost:5141,http://127.0.0.1:5141";

    private CorsConstants() {
    }
}
