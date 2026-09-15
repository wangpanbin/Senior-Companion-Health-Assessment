package org.company.nianglin.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 锁定全局 JSON 时间格式。
 *
 * <p>{@link JacksonConfig} 解决的是「{@code spring.jackson.date-format} 管不到
 * {@code LocalDateTime}」这个坑。这类配置的典型翻车方式是：有人后来在别处
 * 注册了一个自定义 {@code ObjectMapper} Bean，把它顶掉了，接口悄悄退回 ISO 格式，
 * 而没有任何测试报警。所以这里逐条锁死，改动该配置必然红。</p>
 *
 * <p>本类同时锁定 {@code spring.jackson.default-property-inclusion: non_null}
 * 的行为（值为 null 的字段整体不出现在响应里），因为前端是按「字段不存在」
 * 而不是「字段为 null」来判断的。</p>
 *
 * @author 银龄伴诊团队
 * @since M3
 */
@SpringBootTest
@DisplayName("全局 JSON 时间格式：LocalDateTime 必须是 yyyy-MM-dd HH:mm:ss")
class JacksonDateTimeFormatTest {

    @Autowired
    private ObjectMapper objectMapper;

    /** 测试用载体，字段名与业务 VO 保持一致 */
    @SuppressWarnings("unused")
    private static class TimeHolder {
        public LocalDateTime createTime;
        public LocalDate birthDate;
        public LocalTime planTime;
        public String nickname;

        TimeHolder(LocalDateTime createTime, LocalDate birthDate, LocalTime planTime, String nickname) {
            this.createTime = createTime;
            this.birthDate = birthDate;
            this.planTime = planTime;
            this.nickname = nickname;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 序列化（出参）                                                        */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("LocalDateTime → yyyy-MM-dd HH:mm:ss（不是 ISO 带 T 的写法）")
    void localDateTimeShouldUseSpaceSeparatedPattern() throws Exception {
        String json = objectMapper.writeValueAsString(
                new TimeHolder(LocalDateTime.of(2026, 9, 15, 22, 57, 48), null, null, null));

        assertTrue(json.contains("\"createTime\":\"2026-09-15 22:57:48\""), json);
        assertFalse(json.contains("T22:57:48"), "不允许出现 ISO-8601 的 T 分隔符：" + json);
    }

    @Test
    @DisplayName("LocalDate → yyyy-MM-dd，LocalTime → HH:mm:ss")
    void localDateAndTimeShouldUseOwnPatterns() throws Exception {
        String json = objectMapper.writeValueAsString(
                new TimeHolder(null, LocalDate.of(1948, 3, 12), LocalTime.of(8, 5, 0), null));

        assertTrue(json.contains("\"birthDate\":\"1948-03-12\""), json);
        assertTrue(json.contains("\"planTime\":\"08:05:00\""), json);
    }

    @Test
    @DisplayName("值为 null 的字段整体不出现（default-property-inclusion: non_null）")
    void nullFieldsShouldBeOmitted() throws Exception {
        String json = objectMapper.writeValueAsString(
                new TimeHolder(null, null, null, "家属01"));

        assertFalse(json.contains("createTime"), json);
        assertFalse(json.contains("birthDate"), json);
        assertTrue(json.contains("\"nickname\":\"家属01\""), json);
    }

    /* ------------------------------------------------------------------ */
    /* 反序列化（入参，刻意放宽）                                             */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("入参接受空格分隔的标准写法")
    void shouldParseStandardPattern() throws Exception {
        TimeHolder holder = objectMapper.readValue(
                "{\"createTime\":\"2026-09-15 22:57:48\"}", TimeHolder.class);

        assertEquals(LocalDateTime.of(2026, 9, 15, 22, 57, 48), holder.createTime);
    }

    @Test
    @DisplayName("入参兼容历史 ISO 写法（带 T）")
    void shouldParseIsoPattern() throws Exception {
        TimeHolder holder = objectMapper.readValue(
                "{\"createTime\":\"2026-09-15T22:57:48\"}", TimeHolder.class);

        assertEquals(LocalDateTime.of(2026, 9, 15, 22, 57, 48), holder.createTime);
    }

    @Test
    @DisplayName("入参兼容省略秒的写法")
    void shouldParsePatternWithoutSeconds() throws Exception {
        TimeHolder holder = objectMapper.readValue(
                "{\"createTime\":\"2026-09-15 22:57\"}", TimeHolder.class);

        assertEquals(LocalDateTime.of(2026, 9, 15, 22, 57, 0), holder.createTime);
    }

    /* ------------------------------------------------------------------ */
    /* 往返一致性                                                            */
    /* ------------------------------------------------------------------ */

    @Test
    @DisplayName("序列化 → 反序列化 往返一致（出参与入参口径能对上）")
    void roundTripShouldBeStable() throws Exception {
        LocalDateTime original = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        String json = objectMapper.writeValueAsString(
                new TimeHolder(original, null, null, null));
        TimeHolder back = objectMapper.readValue(json, TimeHolder.class);

        assertEquals(original, back.createTime);
    }
}
