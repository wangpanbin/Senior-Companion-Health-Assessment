package org.company.nianglin.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.company.nianglin.common.BaseEntity;

/**
 * SysFile —— 对应表 {@code sys_file}。
 *
 * <p>附件统一登记表</p>
 *
 * <p>主键与审计字段（id / createTime / updateTime / deleted）继承自 {@link BaseEntity}。</p>
 *
 * <p>⚠️ 本类为持久化实体，禁止直接作为接口返回值；对外统一转 VO，
 * 避免密码、身份证号、完整手机号等敏感字段外泄。</p>
 *
 * @since M1
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_file")
public class SysFile extends BaseEntity {

    /**
     * 对外暴露的文件标识（上传接口返回的 fileId）
     */
    @Schema(description = "对外暴露的文件标识（上传接口返回的 fileId）")
    private String fileId;

    /**
     * 原始文件名（仅记录，不用于落盘路径）
     */
    @Schema(description = "原始文件名（仅记录，不用于落盘路径）")
    private String originName;

    /**
     * 磁盘存储文件名（UUID 重命名，防路径穿越）
     */
    @Schema(description = "磁盘存储文件名（UUID 重命名，防路径穿越）")
    private String storedName;

    /**
     * 可访问 URL，如 /uploads/202609/xxx.jpg
     */
    @Schema(description = "可访问 URL，如 /uploads/202609/xxx.jpg")
    private String url;

    /**
     * 磁盘相对路径 ./uploads/{yyyyMM}/{uuid}.{ext}
     */
    @Schema(description = "磁盘相对路径 ./uploads/{yyyyMM}/{uuid}.{ext}")
    private String storePath;

    /**
     * 字节数
     */
    @Schema(description = "字节数")
    private Long size;

    /**
     * 扩展名：jpg / png / webp / pdf
     */
    @Schema(description = "扩展名：jpg / png / webp / pdf")
    private String ext;

    /**
     * 服务端探测到的真实 MIME（按文件头判定，不信任前端）
     */
    @Schema(description = "服务端探测到的真实 MIME（按文件头判定，不信任前端）")
    private String contentType;

    /**
     * 业务类型：COMPANION_CERT-资质证件 / CHECKIN-打卡照片 / COMPLAINT-投诉证据 / AVATAR-头像 / REVIEW-评价图片
     */
    @Schema(description = "业务类型：COMPANION_CERT-资质证件 / CHECKIN-打卡照片 / COMPLAINT-投诉证据 / AVATAR-头像 / REVIEW-评价图片")
    private String bizType;

    /**
     * 业务主键 ID
     */
    @Schema(description = "业务主键 ID")
    private Long bizId;

    /**
     * 上传人用户 ID
     */
    @Schema(description = "上传人用户 ID")
    private Long uploaderId;

}
