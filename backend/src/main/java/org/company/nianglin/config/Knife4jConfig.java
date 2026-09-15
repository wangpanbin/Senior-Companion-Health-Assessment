package org.company.nianglin.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档配置（Knife4j / OpenAPI 3）。
 *
 * <p>文档地址：{@code http://localhost:8080/doc.html}</p>
 *
 * <p>分组与服务端包结构、{@code docs/api/} 目录一一对应，方便前后端按模块联调。</p>
 *
 * @author 银龄伴诊团队
 */
@Configuration
public class Knife4jConfig {

    private static final String SECURITY_SCHEME_NAME = "JWT";

    @Bean
    public OpenAPI nianglinOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("银龄伴诊 —— 就医陪诊与用药协同管理平台 API")
                        .description("""
                                老年人就医陪诊与用药协同管理平台后端接口。

                                **统一约定**
                                - 统一前缀 `/api`
                                - 鉴权：请求头 `Authorization: Bearer <token>`
                                - 统一响应：`{ "code": 200, "message": "success", "data": ... }`
                                - 分页响应：`data` 为 `{ total, page, size, pages, records }`
                                - 角色：`ELDER` / `FAMILY` / `COMPANION` / `ADMIN`
                                - 订单状态机：待接单 → 已接单 → 服务中 → 已完成 → 已评价（禁止跳级、禁止回退）

                                **合规约束**
                                - 不做诊断、不开药方，药品信息仅为通用资料
                                - 密码 BCrypt；手机号脱敏；接口最小化返回；日志不打印身份证号

                                详细约定与错误码见 `docs/api/README.md`
                                """)
                        .version("v0.1.0")
                        .contact(new Contact().name("银龄伴诊团队 · 软件工程课程设计"))
                        .license(new License().name("课程作业 · 仅用于教学演示")))
                // 全局安全方案：右上角「Authorize」填入 token 即可调试需要登录的接口
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .schemaRequirement(SECURITY_SCHEME_NAME, new SecurityScheme()
                        .name(SECURITY_SCHEME_NAME)
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .in(SecurityScheme.In.HEADER)
                        .description("登录接口返回的 accessToken，填写时不需要手动加 'Bearer ' 前缀"));
    }

    /* ==================== 按模块分组 ==================== */

    @Bean
    public GroupedOpenApi authApi() {
        return group("01-认证与账号", "org.company.nianglin.controller.auth");
    }

    @Bean
    public GroupedOpenApi userApi() {
        return group("02-用户与档案", "org.company.nianglin.controller.user");
    }

    @Bean
    public GroupedOpenApi orderApi() {
        return group("03-陪诊订单", "org.company.nianglin.controller.order");
    }

    @Bean
    public GroupedOpenApi executionApi() {
        return group("04-陪诊执行与打卡", "org.company.nianglin.controller.execution");
    }

    @Bean
    public GroupedOpenApi medicationApi() {
        return group("05-用药管理", "org.company.nianglin.controller.medication");
    }

    @Bean
    public GroupedOpenApi reviewApi() {
        return group("06-评价与投诉", "org.company.nianglin.controller.review");
    }

    @Bean
    public GroupedOpenApi messageApi() {
        return group("07-站内信", "org.company.nianglin.controller.message");
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return group("08-管理后台", "org.company.nianglin.controller.admin");
    }

    @Bean
    public GroupedOpenApi statisticsApi() {
        return group("09-数据统计与导出", "org.company.nianglin.controller.statistics");
    }

    @Bean
    public GroupedOpenApi commonApi() {
        return GroupedOpenApi.builder()
                .group("00-通用与健康检查")
                .packagesToScan("org.company.nianglin.controller.common")
                .build();
    }

    private GroupedOpenApi group(String name, String basePackage) {
        return GroupedOpenApi.builder()
                .group(name)
                .packagesToScan(basePackage)
                .build();
    }
}
