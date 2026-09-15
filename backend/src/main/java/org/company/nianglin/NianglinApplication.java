package org.company.nianglin;

import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * 银龄伴诊 —— 老年人就医陪诊与用药协同管理平台
 *
 * <p>工程骨架启动类。本阶段仅包含基础设施（统一响应、异常处理、接口文档、ORM / 缓存 / 安全配置），
 * 业务逻辑自 M2 起按模块落地。</p>
 *
 * @author 银龄伴诊团队
 */
@Slf4j
@EnableAsync
@EnableScheduling
@MapperScan("org.company.nianglin.mapper")
@SpringBootApplication
public class NianglinApplication {

    public static void main(String[] args) {
        Environment env = SpringApplication.run(NianglinApplication.class, args).getEnvironment();
        printStartupInfo(env);
    }

    private static void printStartupInfo(Environment env) {
        String port = env.getProperty("server.port", "8080");
        String profiles = String.join(",", env.getActiveProfiles());
        String host;
        try {
            host = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            host = "localhost";
        }

        log.info("""

                        ----------------------------------------------------------
                          银龄伴诊 后端服务启动成功
                        ----------------------------------------------------------
                          运行环境 : {}
                          本机地址 : http://{}:{}
                          健康检查 : http://localhost:{}/api/health
                          接口文档 : http://localhost:{}/doc.html
                        ----------------------------------------------------------
                        """, profiles, host, port, port, port);
    }
}
