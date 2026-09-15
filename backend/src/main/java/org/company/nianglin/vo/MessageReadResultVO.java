package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 标记已读结果（{@code docs/api/07-message.md} §3 / §4）。
 *
 * <p>两个动作只差一个 {@code affected}：单条已读恒为 1（或者幂等地为 0），
 * 全部已读可能是 0~N。放在同一个 VO 里是因为前端处理方式完全一样 ——
 * 拿到 {@code unreadCount} 直接覆盖顶栏数字，不需要为两个接口写两套更新逻辑。</p>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
@Data
@Accessors(chain = true)
@Schema(description = "标记已读结果")
public class MessageReadResultVO {

    @Schema(description = "本次实际影响的消息条数。单条已读若消息本就已读，返回 0（幂等）", example = "1")
    private Integer affected;

    @Schema(description = "操作后的未读总数，前端直接用它刷新顶栏红点", example = "6")
    private Long unreadCount;

    public static MessageReadResultVO of(int affected, long unreadCount) {
        return new MessageReadResultVO()
                .setAffected(affected)
                .setUnreadCount(unreadCount);
    }
}
