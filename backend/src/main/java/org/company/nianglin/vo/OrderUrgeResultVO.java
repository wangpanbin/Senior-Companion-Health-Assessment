package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/**
 * 催单结果（V3.1 · T3）。
 *
 * <p>把「下次最早什么时候能再催」一并返回，是为了让前端不必自己算冷却期 ——
 * 冷却规则在服务端，前端复制一份必然会随配置调整而漂移。</p>
 *
 * @author 银龄伴诊团队
 * @since V3.1
 */
@Data
@Accessors(chain = true)
@Schema(description = "催单结果")
public class OrderUrgeResultVO {

    @Schema(description = "订单 ID")
    private Long orderId;

    @Schema(description = "累计催单次数（含本次）", example = "1")
    private Integer urgeCount;

    @Schema(description = "本次催单时间")
    private LocalDateTime lastUrgeAt;

    @Schema(description = "下次最早可催时间；已达次数上限时为 null")
    private LocalDateTime nextUrgeAvailableAt;

    public static OrderUrgeResultVO of(Long orderId, Integer urgeCount,
                                       LocalDateTime lastUrgeAt,
                                       LocalDateTime nextUrgeAvailableAt) {
        OrderUrgeResultVO vo = new OrderUrgeResultVO();
        vo.setOrderId(orderId);
        vo.setUrgeCount(urgeCount);
        vo.setLastUrgeAt(lastUrgeAt);
        vo.setNextUrgeAvailableAt(nextUrgeAvailableAt);
        return vo;
    }
}
