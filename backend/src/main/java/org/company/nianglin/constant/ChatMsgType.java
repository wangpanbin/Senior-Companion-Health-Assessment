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
}
