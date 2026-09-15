package org.company.nianglin.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;
import org.company.nianglin.entity.SysFile;

/**
 * 文件上传结果（{@code docs/api/04-companion-execution.md} §5 响应）。
 *
 * <h3>为什么只回三个字段</h3>
 *
 * <p>返 {@code fileId} 是为了让业务表（打卡照片、投诉证据）能引用同一个文件，
 * 返 {@code url} 是为了前端能立刻预览，返 {@code size} 是为了前端能显示占用。
 * 除此之外没有任何一项对上传方有用 ——
 * {@code storedName} / {@code storePath} 是服务端落盘细节，
 * 暴露出去等于把目录结构告诉别人。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@Data
@Accessors(chain = true)
@Schema(description = "文件上传结果")
public class FileUploadVO {

    @Schema(description = "文件标识，业务表引用它", example = "f_8001")
    private String fileId;

    @Schema(description = "可访问 URL", example = "/uploads/202609/def456.jpg")
    private String url;

    @Schema(description = "字节数", example = "204800")
    private Long size;

    public static FileUploadVO of(SysFile file) {
        if (file == null) {
            return null;
        }
        return new FileUploadVO()
                .setFileId(file.getFileId())
                .setUrl(file.getUrl())
                .setSize(file.getSize());
    }
}
