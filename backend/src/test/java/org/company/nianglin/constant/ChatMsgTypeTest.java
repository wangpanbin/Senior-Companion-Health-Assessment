package org.company.nianglin.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("聊天消息类型：预览文案单一口径")
class ChatMsgTypeTest {

    @Test
    @DisplayName("媒体消息用固定方括号文案，不透传说明文字")
    void previewOfShouldUseFixedMediaCopy() {
        assertEquals("[图片]", ChatMsgType.previewOf("IMAGE", "这张化验单我看不懂"));
        assertEquals("[视频]", ChatMsgType.previewOf("video", null));
    }

    @Test
    @DisplayName("文字预览含省略号仍不超过三十个完整字符")
    void previewOfShouldTruncateTextToThirtyCodePoints() {
        String text = "\uD840\uDC00".repeat(31);
        assertEquals("\uD840\uDC00".repeat(29) + "…", ChatMsgType.previewOf("TEXT", text));
    }

    @Test
    @DisplayName("未知或空类型按文字兜底，不打断提醒链路")
    void previewOfShouldFallBackToText() {
        assertEquals("已到医院", ChatMsgType.previewOf(null, "已到医院"));
        assertEquals("已到医院", ChatMsgType.previewOf("NOPE", "已到医院"));
    }

    @Test
    @DisplayName("文字为 null 时预览为 null，不显示成空串")
    void previewOfShouldKeepNullTextNull() {
        assertNull(ChatMsgType.previewOf("TEXT", null));
    }
}
