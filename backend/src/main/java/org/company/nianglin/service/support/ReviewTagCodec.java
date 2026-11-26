package org.company.nianglin.service.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 评价标签 JSON 的编解码（评价读写两侧共用的唯一一份）。
 *
 * <h3>为什么抽出来</h3>
 *
 * <p>{@code order_review.tags} 存的是 JSON 数组串，读侧有两处（公开口碑列表
 * {@code ReviewServiceImpl}、管理端巡查列表 {@code AdminServiceImpl}）。
 * 两处各自实现一份解析，注释里都写着「与对方同口径」——
 * 而「同口径」靠注释维持是最脆的一种耦合：字段格式一变，改了一处忘了另一处，
 * 表现是「同一条评价在两个页面上标签一个有一个没有」，不报错、不告警。</p>
 *
 * <p>所以收敛到这一个类。它只做两件事：解析（脏数据退化为空列表，不把列表打挂）
 * 与序列化（失败抛业务异常，因为写侧不能静默丢标签）。</p>
 *
 * @author 银龄伴诊团队
 * @since E4
 */
@Slf4j
public final class ReviewTagCodec {

    /** 标签数组的 JSON 形态；两处读侧共用同一份 TypeReference */
    private static final TypeReference<List<String>> TAG_LIST = new TypeReference<>() {
    };

    private ReviewTagCodec() {
    }

    /**
     * 解析标签 JSON。
     *
     * <p>内容损坏时按空列表处理 —— 一条脏数据不该让整个列表接口 500。
     * 日志只记长度与异常摘要，不记原文（标签里可能带评价正文片段）。</p>
     *
     * @param json 数据库里的原值；{@code null} 或空白返回空列表
     * @param mapper 调用方的 {@link ObjectMapper}（复用容器里的那一个）
     * @return 非 {@code null} 的标签列表
     */
    public static List<String> parse(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> parsed = mapper.readValue(json, TAG_LIST);
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            log.warn("评价标签 JSON 解析失败，已按空处理 | len={} | {}", json.length(), e.getMessage());
            return List.of();
        }
    }

    /**
     * 序列化标签列表。
     *
     * @param tags   标签列表；{@code null} 序列化为 {@code null}
     * @param mapper 调用方的 {@link ObjectMapper}
     * @return JSON 串或 {@code null}
     * @throws IllegalStateException 序列化失败（写侧不能静默丢标签，由调用方转成业务异常）
     */
    public static String serialize(List<String> tags, ObjectMapper mapper) {
        if (tags == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(tags);
        } catch (Exception e) {
            throw new IllegalStateException("评价标签序列化失败", e);
        }
    }
}
