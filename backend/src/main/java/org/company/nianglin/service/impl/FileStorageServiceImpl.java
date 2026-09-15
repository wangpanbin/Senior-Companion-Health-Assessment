package org.company.nianglin.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.entity.SysFile;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.SysFileMapper;
import org.company.nianglin.service.FileStorageService;
import org.company.nianglin.vo.FileUploadVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 附件存储实现。
 *
 * <h3>类型判定只看文件头</h3>
 *
 * <p>前端传来的 {@code Content-Type} 与文件扩展名都是<b>客户端说了算</b>的：
 * 把 {@code shell.jsp} 改名成 {@code a.jpg} 就能过扩展名白名单，
 * 把 {@code Content-Type} 写成 {@code image/jpeg} 更是 curl 一行的事。
 * 只认前几个字节的 magic number —— 那才是「这个文件究竟是什么」的物理证据。</p>
 *
 * <h3>文件名一律 UUID</h3>
 *
 * <p>原始文件名有两个问题：可以含 {@code ../} 造成路径穿越，
 * 也可以在多人同时上传时互相覆盖。UUID 重命名一次解决两个，
 * 原始名只作为 {@code origin_name} 记录下来供人工核对。</p>
 *
 * @author 银龄伴诊团队
 * @since M5
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageServiceImpl implements FileStorageService {

    private static final DateTimeFormatter MONTH_DIR_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

    /** 识别文件头所需的最少字节数（WebP 需要看到第 12 字节） */
    private static final int MIN_HEADER_BYTES = 12;

    private final SysFileMapper sysFileMapper;

    @Value("${nianglin.file.upload-dir:./uploads}")
    private String uploadDir;

    @Override
    public FileUploadVO store(MultipartFile file, String bizType, Long bizId, Long uploaderId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请选择要上传的文件");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.warn("读取上传文件失败 | bizType={} | bizId={} | {}", bizType, bizId, e.getMessage());
            throw new BusinessException(ResultCode.PARAM_ERROR, "文件读取失败，请重试");
        }

        String ext = detectExtension(bytes);
        if (ext == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "文件类型不允许（仅支持 jpg / png / webp / pdf）");
        }

        String monthDir = LocalDateTime.now().format(MONTH_DIR_FORMAT);
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = Paths.get(uploadDir, monthDir).resolve(storedName);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            log.error("文件落盘失败 | dir={} | {}", target.getParent(), e.getMessage());
            throw new BusinessException(ResultCode.SYSTEM_ERROR, "文件保存失败，请稍后重试");
        }

        SysFile record = new SysFile();
        record.setFileId("f_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        record.setOriginName(clearable(file.getOriginalFilename()));
        record.setStoredName(storedName);
        record.setUrl("/uploads/" + monthDir + "/" + storedName);
        record.setStorePath(uploadDir + "/" + monthDir + "/" + storedName);
        record.setSize(file.getSize());
        record.setExt(ext);
        record.setContentType(file.getContentType());
        record.setBizType(bizType);
        record.setBizId(bizId);
        record.setUploaderId(uploaderId);
        sysFileMapper.insert(record);

        log.info("文件已上传 | bizType={} | bizId={} | size={} | ext={}", bizType, bizId, bytes.length, ext);
        return FileUploadVO.of(record);
    }

    /**
     * 按文件头判定真实类型。
     *
     * @return 归一化后的扩展名，无法识别返回 {@code null}
     */
    static String detectExtension(byte[] bytes) {
        if (bytes == null || bytes.length < MIN_HEADER_BYTES) {
            return null;
        }
        // JPEG: FF D8 FF
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        // PNG: 89 50 4E 47
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "png";
        }
        // WebP: "RIFF" …… "WEBP"
        if (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "webp";
        }
        // PDF: "%PDF"
        if (bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F') {
            return "pdf";
        }
        return null;
    }

    private static String clearable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
