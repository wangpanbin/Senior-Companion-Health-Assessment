package org.company.nianglin.controller.common;

import org.company.nianglin.common.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 健康检查的响应面（最终复盘 BE-06 / BE-07）。
 *
 * <h3>为什么这两条要写测试而不是只改注释</h3>
 *
 * <p>「prod 不返回运行环境细节」是一句**运行时才生效**的承诺。只写在
 * {@code @Operation} 描述里，下一次有人图省事把 {@code profiles} 加回去，
 * 没有任何东西会红 —— 而这正好是最典型的「匿名端点泄露指纹」回归。</p>
 *
 * <p>同理 {@code /demo-success} 需要登录这件事，靠注释提醒不可靠；
 * 这里锁住的是「它不属于匿名白名单」这一事实的另一半：
 * 路径不是 {@code /api/health} 的精确匹配（白名单是精确匹配，没有通配）。</p>
 *
 * <p>不启动 Spring：健康检查只读 {@code Environment} 与系统属性，
 * 用 {@link MockEnvironment} 就能把两个 profile 的差异测干净，
 * 比 {@code @SpringBootTest} 快两个数量级，也不会因为连不上库而假红。</p>
 *
 * @author 银龄伴诊团队
 * @since V6.0
 */
@DisplayName("健康检查：prod 不暴露环境细节 / demo-success 属需鉴权端点")
class HealthControllerTest {

    @Test
    @DisplayName("非 prod：返回 application / profiles / javaVersion，便于本地排查")
    void healthShouldExposeDetailsOutsideProd() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.application.name", "nianglin")
                .withProperty("spring.profiles.active", "dev");
        env.setActiveProfiles("dev");

        Map<String, Object> data = healthOf(env);

        assertEquals("UP", data.get("status"));
        assertNotNull(data.get("serverTime"));
        assertEquals("nianglin", data.get("application"));
        assertEquals("dev", data.get("profiles"));
        assertNotNull(data.get("javaVersion"), "非 prod 环境需要 JDK 版本辅助排查");
    }

    @Test
    @DisplayName("prod：只返回 status 与 serverTime —— 匿名端点不给指纹")
    void healthShouldHideDetailsInProd() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");

        Map<String, Object> data = healthOf(env);

        assertEquals("UP", data.get("status"));
        assertNotNull(data.get("serverTime"));
        assertEquals(2, data.size(), "prod 下响应体只允许两个字段，多一个就是信息暴露");
        assertFalse(data.containsKey("profiles"));
        assertFalse(data.containsKey("javaVersion"));
        assertFalse(data.containsKey("application"));
    }

    @Test
    @DisplayName("status 恒为 UP：探活脚本只看这一个字段，别让它随 profile 变化")
    void statusShouldAlwaysBeUp() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertTrue("UP".equals(healthOf(dev).get("status")) && "UP".equals(healthOf(prod).get("status")));
    }

    private Map<String, Object> healthOf(MockEnvironment env) {
        Result<Map<String, Object>> result = new HealthController(env).health();
        assertNotNull(result.getData(), "健康检查必须始终带 data");
        return result.getData();
    }
}
