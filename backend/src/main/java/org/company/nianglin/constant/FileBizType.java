package org.company.nianglin.constant;

import java.util.Arrays;

/**
 * 文件上传业务类型（通用上传端点的白名单）。
 *
 * <p>{@code sys_file.biz_type} 列注释定义了 5 个取值：
 * {@code COMPANION_CERT}(资质证件) / {@code CHECKIN}(打卡照片) /
 * {@code COMPLAINT}(投诉证据) / {@code AVATAR}(头像) / {@code REVIEW}(评价图片)。</p>
 *
 * <p><b>本通用端点只放行其中 3 个</b>，刻意<b>不放行</b> {@code CHECKIN} 与 {@code REVIEW}：</p>
 * <ul>
 *   <li>{@code CHECKIN}：打卡照片有专属入口
 *       {@code POST /api/execution/{orderId}/photo}，那里会校验「须为本单陪诊员」，
 *       否则返回 4003。若通用端点也收 CHECKIN，任何人都能往任意订单塞打卡照片，
 *       直接绕过归属校验。</li>
 *   <li>{@code REVIEW}：一期评价无图片字段，放开等于凭空造出一条无法关联的业务数据。</li>
 * </ul>
 *
 * <p>资质证件、投诉证据、头像三类没有归属校验需求，由调用方持有返回的 {@code url}
 * 自行关联到具体业务（如 {@code certificates[].url}、{@code complaint.evidence[]}），
 * 因此统一走本通用端点即可。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
public enum FileBizType {

    /** 陪诊员资质证件 */
    COMPANION_CERT("资质证件"),
    /** 投诉证据 */
    COMPLAINT("投诉证据"),
    /** 用户头像 */
    AVATAR("头像"),
    ;

    private final String label;

    FileBizType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    /** 按枚举名反查（忽略大小写）；不在白名单内（含 CHECKIN / REVIEW 及任何非法值）返回 {@code null} */
    public static FileBizType of(String value) {
        if (value == null) {
            return null;
        }
        return Arrays.stream(values())
                .filter(t -> t.name().equalsIgnoreCase(value))
                .findFirst()
                .orElse(null);
    }
}
