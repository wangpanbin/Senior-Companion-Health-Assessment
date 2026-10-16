package org.company.nianglin.constant;

import java.util.Arrays;

/**
 * 聊天消息类型（M13 订单聊天，ADR-0011）。
 *
 * <p>媒体消息的 {@code msgType} 不是客户端说了算：发送时服务端按附件真实扩展名
 * （来自 magic bytes 判型，见 {@code FileStorageServiceImpl#detectExtension}）反查类型，
 * 与请求声明的 {@code msgType} 不符一律 {@code 7007}。</p>
 *
 * @author 银龄伴诊团队
 * @since M13
 */
public enum ChatMsgType {

    /** 文字（content 必填 ≤ 1000 字，无附件） */
    TEXT("文字"),
    /** 图片（附件 ext ∈ jpg / png / webp，走 bizType=CHAT 上传） */
    IMAGE("图片"),
    /** 视频（附件 ext = mp4，走 bizType=CHAT 上传） */
    VIDEO("视频"),
    ;

    /** 列表 / 提醒的单行预览上限（按完整字符计，切在 30 以内） */
    private static final int PREVIEW_MAX_CODE_POINTS = 30;

    private final String label;

    ChatMsgType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 媒体消息必须携带 fileId */
    public boolean requiresFile() {
        return this != TEXT;
    }

    /** 按枚举名反查（忽略大小写）；非法值返回 {@code null} */
    public static ChatMsgType of(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(t -> t.name().equalsIgnoreCase(value))
                .findFirst()
                .orElse(null);
    }

    /**
     * 按附件扩展名反查应有的消息类型。
     *
     * @return 图片扩展名 → {@link #IMAGE}，mp4 → {@link #VIDEO}，其余（含 pdf 等）→ {@code null}
     */
    public static ChatMsgType ofExt(String ext) {
        if (ext == null) {
            return null;
        }
        return switch (ext.toLowerCase()) {
            case "jpg", "jpeg", "png", "webp" -> IMAGE;
            case "mp4" -> VIDEO;
            default -> null;
        };
    }

    /**
     * 会话列表与站内信提醒用的消息预览（唯一口径）。
     *
     * <p>媒体消息固定 {@code [图片] / [视频]}，<b>不透传</b>用户输入的说明文字 —— 列表只有一行，
     * 长文案会把行撑爆，正文进聊天室看。未知 {@code msgType} 按 {@link #TEXT} 兜底而不抛异常：
     * 预览是展示旁路，不该因为一个脏枚举值把整条提醒链路打断。</p>
     *
     * @param msgType 消息类型枚举名（忽略大小写；{@code null} / 非法值按 {@link #TEXT} 处理）
     * @param content 消息正文（媒体消息为说明文字，可为 {@code null}）
     */
    public static String previewOf(String msgType, String content) {
        ChatMsgType type = of(msgType);
        return switch (type == null ? TEXT : type) {
            case IMAGE -> "[图片]";
            case VIDEO -> "[视频]";
            case TEXT -> truncate(content);
        };
    }

    /** 按码点截断，避免把代理对劈成半个字符 */
    private static String truncate(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.trim();
        return trimmed.codePointCount(0, trimmed.length()) <= PREVIEW_MAX_CODE_POINTS
                ? trimmed
                : trimmed.substring(0, trimmed.offsetByCodePoints(0, PREVIEW_MAX_CODE_POINTS - 1)) + "…";
    }
}
