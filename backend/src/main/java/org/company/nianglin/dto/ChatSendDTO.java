package org.company.nianglin.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
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
}
