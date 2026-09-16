package org.company.nianglin.controller.common;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.FileBizType;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.security.SecurityUtils;
import org.company.nianglin.service.FileStorageService;
import org.company.nianglin.vo.FileUploadVO;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.StringJoiner;

/**
 * 通用文件上传。
 *
 * <p><b>为什么需要它</b>：{@code FileStorageService.store(...)} 早已实现
 * 「校验文件头 → UUID 重命名 → 落盘 → 落 sys_file 表」这一套，但一直缺一个对外入口。
 * {@code CertificateItem.url} 的注释明确写着「来自文件上传接口」，
 * 可见这个端点本就是设计内的必选项，只是漏实现了。</p>
 *
 * <p>⚠️ <b>默认安全，无需额外拦截 ELDER</b>：{@code ElderReadOnlyInterceptor} 已对
 * {@code /api/**} 的<b>非 GET</b> 请求默认拒绝 ELDER（见 {@code WebMvcConfig#addInterceptors}），
 * 本写接口天然被拦。所以这里<b>不必、也不应该</b>再写一道 ELDER 专属拦截 ——
 * 那会破坏「新增接口默认安全」的全局约定。</p>
 *
 * <p>⚠️ <b>刻意不接收 {@code bizId}</b>：客户端传来的 bizId 服务端无从校验真伪，
 * 直接写进 {@code sys_file.biz_id} 就是脏数据（例如把别人的订单 id 填进来）。
 * 真正的业务关联由业务侧自己持有接口返回的 {@code url} 完成
 * （如 {@code certificates[].url}、{@code complaint.evidence[]}），本端点只负责「存」。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@Slf4j
@RestController
@RequestMapping("/api/file")
@RequiredArgsConstructor
@Tag(name = "通用-文件上传", description = "资质证件 / 投诉证据 / 头像的上传入口")
public class FileController {

    private final FileStorageService fileStorageService;

    @Operation(summary = "上传文件",
            description = "仅家属 / 陪诊员 / 管理员可上传。bizType 限 COMPANION_CERT / COMPLAINT / AVATAR 三种；"
                    + "不接收 bizId（关联由业务侧持 url 自行完成）")
    @PreAuthorize("hasAnyRole('" + RoleConstants.FAMILY + "','" + RoleConstants.COMPANION + "','" + RoleConstants.ADMIN + "')")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<FileUploadVO> upload(@RequestParam("file") MultipartFile file,
                                       @RequestParam("bizType") String bizType) {
        FileBizType type = FileBizType.of(bizType);
        if (type == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR,
                    "不支持的业务类型 bizType，允许取值：" + allowedBizTypes());
        }
        FileUploadVO vo = fileStorageService.store(file, type.name(), null, SecurityUtils.currentUserId());
        log.info("文件上传成功 | bizType={} | uploaderId={} | fileId={}",
                type.name(), SecurityUtils.currentUserId(), vo.getFileId());
        return Result.success(vo);
    }

    /** 把白名单枚举拼成可读字符串，供错误提示列出允许取值 */
    private static String allowedBizTypes() {
        StringJoiner joiner = new StringJoiner(" / ");
        for (FileBizType t : FileBizType.values()) {
            joiner.add(t.name());
        }
        return joiner.toString();
    }
}
