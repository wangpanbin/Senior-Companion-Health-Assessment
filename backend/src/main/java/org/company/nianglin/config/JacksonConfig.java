package org.company.nianglin.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

/**
 * 全局 JSON 时间格式配置。
 *
 * <h3>为什么必须有这个类</h3>
 *
 * <p>{@code spring.jackson.date-format} 只作用于旧的 {@code java.util.Date}，
 * 对 JSR-310 的 {@link LocalDateTime} <b>完全无效</b> —— Jackson 会退回默认的
 * ISO-8601 格式（{@code 2026-09-15T22:57:48}，中间带一个 {@code T}）。</p>
 *
 * <p>而本项目所有实体（{@link org.company.nianglin.common.BaseEntity} 及各业务表）用的都是
 * {@code LocalDateTime}。若不在这里显式注册序列化器，接口会输出带 {@code T} 的字符串，
 * 与 {@code docs/api/} 全部 10 篇文档里写的 {@code "2026-09-15 22:57:48"} 不一致，
 * 前端（Element Plus 的日期组件、表格列）也要额外做一次转换。因此统一在此收口。</p>
 *
 * <h3>输出格式（与接口文档一致，勿随意改动）</h3>
 *
 * <ul>
 *   <li>{@code LocalDateTime} → {@code yyyy-MM-dd HH:mm:ss}</li>
 *   <li>{@code LocalDate} → {@code yyyy-MM-dd}</li>
 *   <li>{@code LocalTime} → {@code HH:mm:ss}</li>
 * </ul>
 *
 * <h3>入参为什么比出参宽松</h3>
 *
 * <p>出参是给别人读的，必须严格统一；入参是别人送进来的，宽容一点更不容易炸。
 * 所以 {@link LocalDateTime} 的反序列化额外兼容三种写法：
 * {@code 2026-09-15 22:57:48}、{@code 2026-09-15 22:57}（省略秒）、
 * {@code 2026-09-15T22:57:48}（历史 ISO 写法，避免老调用方直接 400）。</p>
 *
 * <p>注意：本项目一期对外的时间字段基本都是字符串形式（如
 * {@code ElderCreateDTO.birthDate} 用 {@code yyyy-MM-dd} 字符串 + 正则校验），
 * 因此这里的反序列化器主要服务于后续模块（订单预约时间等）与测试代码。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@Configuration
public class JacksonConfig {

    /** 日期时间输出格式（接口文档统一口径） */
    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    /** 日期输出格式 */
    public static final String DATE_PATTERN = "yyyy-MM-dd";
    /** 时间输出格式 */
    public static final String TIME_PATTERN = "HH:mm:ss";

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern(DATE_PATTERN);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern(TIME_PATTERN);

    /**
     * 宽容的日期时间解析器：分隔符（空格 / {@code T}）与秒都可省略。
     *
     * <pre>
     *   2026-09-15 22:57:48   ✓
     *   2026-09-15 22:57      ✓
     *   2026-09-15T22:57:48   ✓
     *   2026-09-15T22:57      ✓
     * </pre>
     */
    private static final DateTimeFormatter LENIENT_DATE_TIME = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd")
            .optionalStart().appendLiteral('T').optionalEnd()
            .optionalStart().appendLiteral(' ').optionalEnd()
            .appendPattern("HH:mm")
            .optionalStart().appendPattern(":ss").optionalEnd()
            .toFormatter();

    /**
     * 把 JSR-310 序列化/反序列化器注册进 Spring Boot 自动装配的 ObjectMapper。
     *
     * <p>用 {@code Jackson2ObjectMapperBuilderCustomizer} 而不是自定义 {@code ObjectMapper} Bean ——
     * 后者会把 Spring Boot 的其它 json 配置（{@code default-property-inclusion}、
     * {@code write-dates-as-timestamps}、Knife4j 的模块等）一并顶掉。</p>
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer javaTimeJacksonCustomizer() {
        return builder -> builder
                .serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME))
                .serializerByType(LocalDate.class, new LocalDateSerializer(DATE))
                .serializerByType(LocalTime.class, new LocalTimeSerializer(TIME))
                .deserializerByType(LocalDateTime.class, new LocalDateTimeDeserializer(LENIENT_DATE_TIME))
                .deserializerByType(LocalDate.class, new LocalDateDeserializer(DATE))
                .deserializerByType(LocalTime.class, new LocalTimeDeserializer(TIME));
    }
}
