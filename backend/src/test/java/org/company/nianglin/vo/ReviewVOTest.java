package org.company.nianglin.vo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.entity.OrderReview;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ReviewVO 装配与 JSON 序列化单测（E4 评价公信力闭环）。
 *
 * <p>本类盯的是<b>两条新字段装配</b>与<b>两条序列化契约</b>：</p>
 *
 * <ol>
 *   <li><b>isValid 字段映射</b>：{@code is_valid=1} → {@code true}；{@code is_valid=0} →
 *       {@code false}；null 视作有效（兼容 V1 老数据）。规则一旦写错，前端会拿不到
 *       「被裁定无效」的红色标注。</li>
 *   <li><b>replyTime 字段透传</b>：未回复时 {@code replyTime=null}，Jackson
 *       {@code non_null} 自动省略，前端按字段缺失判断「尚未回复」。如果错把
 *       {@code replyTime} 默认成 now()，会让所有评价卡片多一行假时间戳。</li>
 *   <li><b>序列化契约</b>：{@code replyTime=null} 时 JSON 中不出现该 key；否则输出
 *       与既有 {@code createTime} 同款 ISO 字符串（依赖 JacksonConfig）。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since E4
 */
@DisplayName("ReviewVO：isValid 字段映射 / replyTime 透传 / Jackson non_null 契约")
class ReviewVOTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("is_valid=1 → isValid=true（默认值，与既有聚合口径一致）")
    void validReviewShouldExposeIsValidTrue() {
        ReviewVO vo = ReviewVO.of(review(1, null), "张三", "李四", List.of("准时"));
        assertNotNull(vo);
        assertEquals(Boolean.TRUE, vo.getIsValid(),
                "is_valid=1 必须是 true，公开列表与聚合口径都以这个为准");
    }

    @Test
    @DisplayName("is_valid=0 → isValid=false（被裁定无效，前端据此渲染灰色标注）")
    void invalidReviewShouldExposeIsValidFalse() {
        ReviewVO vo = ReviewVO.of(review(0, null), "张三", "李四", List.of("准时"));
        assertFalse(vo.getIsValid(),
                "is_valid=0 必须是 false，前端据此加「平台已裁定为无效评价」灰色标注");
    }

    @Test
    @DisplayName("is_valid=null → isValid=true（兜底兼容 V1 老数据）")
    void nullIsValidShouldBeTreatedAsValid() {
        OrderReview review = review(1, null);
        review.setIsValid(null);
        ReviewVO vo = ReviewVO.of(review, "张三", "李四", List.of());
        assertTrue(vo.getIsValid(),
                "is_valid=null 视作有效，兼容 V1 种子数据里未显式写该字段的记录");
    }

    @Test
    @DisplayName("已回复评价透传 replyTime，未回复时 replyTime=null")
    void replyTimeShouldReflectReplyStatus() {
        LocalDateTime replyAt = LocalDateTime.of(2026, 9, 25, 10, 30);
        ReviewVO replied = ReviewVO.of(review(1, replyAt), "张三", "李四", List.of());
        assertEquals(replyAt, replied.getReplyTime());

        ReviewVO notReplied = ReviewVO.of(review(1, null), "张三", "李四", List.of());
        assertEquals(null, notReplied.getReplyTime(),
                "未回复评价 replyTime 必须为 null，配合 Jackson non_null 自动省略");
    }

    @Test
    @DisplayName("JSON 序列化：replyTime=null 时不出现 replyTime 键（Jackson non_null 契约）")
    void replyTimeShouldBeOmittedWhenNull() throws Exception {
        ReviewVO vo = ReviewVO.of(review(1, null), "张三", "李四", List.of());
        objectMapper.setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL);
        String json = objectMapper.writeValueAsString(vo);

        assertEquals(true, !json.contains("\"replyTime\""),
                "未回复时 replyTime 字段必须不出现 JSON 中，避免前端拿到空值判断混乱");
        assertEquals(true, json.contains("\"isValid\":true"),
                "isValid 必须始终出现（boolean 默认 false 与 null 不混淆）");
    }

    /* ================================================================== */

    private OrderReview review(int isValid, LocalDateTime replyTime) {
        OrderReview r = new OrderReview();
        r.setId(2001L);
        r.setOrderId(1001L);
        r.setOrderNo("NL20260820000031");
        r.setFamilyId(101L);
        r.setElderId(401L);
        r.setCompanionId(307L);
        r.setScore(5);
        r.setIsAnonymous(0);
        r.setIsValid(isValid);
        r.setCompanionReply(replyTime == null ? null : "感谢认可");
        r.setReplyTime(replyTime);
        r.setCreateTime(LocalDateTime.of(2026, 9, 20, 18, 30));
        return r;
    }
}