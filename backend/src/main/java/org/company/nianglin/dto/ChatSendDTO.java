package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
@Schema(description = "发送聊天消息入参")
public class ChatSendDTO {
    @Schema(description = "消息类型：TEXT-文字 / IMAGE-图片 / VIDEO-视频", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "消息类型不能为空")
    private String msgType;

    // 按 Unicode 字符计数并返回业务码 7004，不能用按 UTF-16 长度计数的 @Size。
    @Schema(description = "文字正文或媒体说明，最多1000个Unicode字符")
    private String content;

    @Schema(description = "媒体消息必填：bizType=CHAT 上传获得的 fileId")
    private String fileId;

    /**
     * 客户端消息 ID（幂等键，可选；closure-2026-11 · P2-4）。
     *
     * <p>弱网下发送超时重试会产生两条一样的消息。客户端为每条消息生成一个唯一 ID，
     * 服务端按 {@code (order_id, sender_id, client_msg_id)} 唯一键去重：
     * 重复提交返回<b>已存在的那条</b>，不报错也不重复落库。</p>
     *
     * <p>不传 = 不做幂等（老客户端行为不变）；MySQL 唯一键对 NULL 不约束，
     * 「不传」天然成立。</p>
     */
    @Schema(description = "客户端消息 ID（幂等键，可选；同一订单同一发送人下不重复落库）",
            example = "m-20261128-0001")
    @Size(max = 64, message = "客户端消息 ID 不能超过 64 个字符")
    private String clientMsgId;
}
