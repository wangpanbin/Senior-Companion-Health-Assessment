package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 管理端聊天订单检索项（{@code GET /api/admin/chat/orders}，{@code docs/api/11-order-chat.md} §6）。
 *
 * <p>仲裁查看器左栏列表项。双方姓名均脱敏；管理员要调"是谁"
 * 时去订单详情看（那里有既定的全名豁免），聊天查看器保持最小化返回。</p>
 *
 * @author 银龄伴诊团队
 * @since M13
 */
@Data
@Accessors(chain = true)
@Schema(description = "管理端聊天订单检索项")
public class AdminChatOrderVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "订单 ID（进入只读查看器的路径参数）", example = "1001")
    private Long orderId;

    @Schema(description = "订单号", example = "NL20261015000001")
    private String orderNo;

    @Schema(description = "订单状态中文名", example = "已完成")
    private String orderStatusLabel;

    @Schema(description = "下单家属姓名（脱敏）", example = "王*丰")
    private String familyName;

    @Schema(description = "陪诊员姓名（脱敏）", example = "李*明")
    private String companionName;

    @Schema(description = "就诊医院", example = "海南省人民医院")
    private String hospital;

    @Schema(description = "聊天消息条数", example = "12")
    private Long messageCount;

    @Schema(description = "最后一条消息时间", example = "2026-10-16 12:30:05")
    private LocalDateTime lastMessageTime;
}
