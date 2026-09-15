package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 未读数（{@code docs/api/07-message.md} §2）。
 *
 * <h3>{@code byType} 只列出有未读的类型</h3>
 *
 * <p>九个消息类型里通常只有两三个有未读。返回九个键（其中七个是 0）会让前端
 * 红点渲染逻辑多出一堆判断，而「某个类型有没有未读」这件事本来就该用
 * 「键存不存在」表达。空 map 而不是 {@code null}，前端不用判空。</p>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
@Data
@Accessors(chain = true)
@Schema(description = "站内信未读数")
public class UnreadCountVO {

    @Schema(description = "未读总数", example = "7")
    private Long total;

    @Schema(description = "按类型分组的未读数，只包含有未读的类型",
            example = "{\"ORDER_ACCEPTED\":2,\"MEDICATION_REMIND\":2}")
    private Map<String, Long> byType = new LinkedHashMap<>();

    public static UnreadCountVO of(long total, Map<String, Long> byType) {
        return new UnreadCountVO()
                .setTotal(total)
                .setByType(byType == null ? new LinkedHashMap<>() : byType);
    }
}
