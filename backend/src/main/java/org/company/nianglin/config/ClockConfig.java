package org.company.nianglin.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 时钟 Bean（V4 · P1-3）。
 *
 * <p>短信排程里的静默时段与指数退避全部基于 {@link Clock} 计算，
 * 单测注入固定时钟即可锁定「22:30 入队 → 次日 08:00」这类跨零点行为，
 * 不用 {@code Thread.sleep} 等到真实时间。</p>
 *
 * @author 银龄伴诊团队
 * @since V4 · P1-3
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
