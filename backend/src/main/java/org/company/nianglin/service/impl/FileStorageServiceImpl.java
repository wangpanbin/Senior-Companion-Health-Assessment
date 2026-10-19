package org.company.nianglin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.FileBizType;
import org.company.nianglin.entity.SysFile;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.SysFileMapper;
import org.company.nianglin.service.FileStorageService;
import org.company.nianglin.vo.FileUploadVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.UUID;

/**
 * 附件存储实现。
 *
 * <h3>类型判定只看文件头</h3>
 *
 * <p>前端传来的 {@code Content-Type} 与文件扩展名都是<b>客户端说了算</b>的：
 * 把 {@code shell.jsp} 改名成 {@code a.jpg} 就能过扩展名白名单，
 * 把 {@code Content-Type} 写成 {@code image/jpeg} 更是 curl 一行的事。
 * 只认前几个字节的 magic number —— 那才是「这个文件究竟是什么」的物理证据。
 * mp4 判据是 offset 4 起的 {@code ftyp} box（isom/mp42/MSNV 等 brand 全放行）。</p>
 *
 * <h3>文件名一律 UUID</h3>
 *
 * <p>原始文件名有两个问题：可以含 {@code ../} 造成路径穿越，
 * 也可以在多人同时上传时互相覆盖。UUID 重命名一次解决两个，
 * 原始名只作为 {@code origin_name} 记录下来供人工核对。</p>
 *
 * <h3>分级限额与流式落盘（M13，ADR-0011）</h3>
 *
 * <p>servlet {@code max-file-size} 全局提到 50MB（服务聊天媒体）后，代码层按
 * bizType 分级守住原防线：{@code CHAT ≤ 50MB}，其余一律 {@code ≤ 10MB}。</p>
 *
 * <p>50MB 的文件<b>不允许 {@code getBytes()} 整体读进堆</b>：先从流里读前 12 字节
 * 判型，再流式拷贝剩余字节落盘，堆上任何时刻只有 12 字节的头部缓冲。</p>
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

    /** 非聊天业务的单文件上限（servlet 提额后由代码守住的 10MB 防线） */
    private static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;

    /** 聊天媒体（bizType=CHAT）的单文件上限（ADR-0011 决策 D4） */
    private static final long CHAT_MAX_BYTES = 50L * 1024 * 1024;

    private final SysFileMapper sysFileMapper;

    @Value("${nianglin.file.upload-dir:./uploads}")
    private String uploadDir;

    @Override
    public FileUploadVO store(MultipartFile file, String bizType, Long bizId, Long uploaderId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "请选择要上传的文件");
        }
        checkSizeLimit(file.getSize(), bizType);

        String ext;
        String monthDir;
        String storedName;
        Path target;
        // 判型与落盘必须共用同一条流：只读前 12 字节识别类型，随后从消费点续传。
        // 不能判型后再 file.getInputStream() 开第二条 —— 部分实现会从头再读，
        // 头部 12 字节就会在落盘文件里重复出现（文件损坏）。
        try (InputStream in = file.getInputStream()) {
            byte[] header = in.readNBytes(MIN_HEADER_BYTES);
            ext = detectExtension(header);
            if (ext == null || ("mp4".equals(ext) && FileBizType.of(bizType) != FileBizType.CHAT)) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "文件类型不允许（mp4 仅限订单聊天）");
            }
            monthDir = LocalDateTime.now().format(MONTH_DIR_FORMAT);
            storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
            target = Paths.get(uploadDir, monthDir).resolve(storedName);
            // 目录必须先于输出流创建：newOutputStream 不会自动建父目录，
            // 每月首个文件就会因月份目录不存在而失败
            Files.createDirectories(target.getParent());
            try (OutputStream out = Files.newOutputStream(target)) {
                out.write(header);
                in.transferTo(out);
            }
        } catch (IOException e) {
            log.error("文件读取/落盘失败 | uploadDir={} | {}", uploadDir, e.getMessage());
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

        log.info("文件已上传 | bizType={} | bizId={} | size={} | ext={}", bizType, bizId, file.getSize(), ext);
        return FileUploadVO.of(record);
    }

    @Override
    public CertContent loadCert(String url, Long requesterId, boolean isAdmin) {
        if (url == null || !url.startsWith("/uploads/")) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "证件地址不合法");
        }
        SysFile file = sysFileMapper.selectOne(new LambdaQueryWrapper<SysFile>()
                .eq(SysFile::getUrl, url)
                .last("limit 1"));
        if (file == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "证件不存在");
        }
        // 只有资质证件走受控端点；头像 / 打卡照片 / 聊天媒体仍由 /uploads 公开直连
        if (!FileBizType.COMPANION_CERT.name().equals(file.getBizType())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "该文件不属于受控证件");
        }
        // 授权口径：上传人本人或管理员（资质审核需要查看证件），其余一律拒绝
        if (!isAdmin && !Objects.equals(requesterId, file.getUploaderId())) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权查看该证件");
        }
        Path path = file.getStorePath() == null ? null : Paths.get(file.getStorePath());
        if (path == null || !Files.exists(path)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "证件文件已不存在");
        }
        String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
        String originName = file.getOriginName() == null ? file.getStoredName() : file.getOriginName();
        log.info("受控读取证件 | fileId={} | requesterId={} | admin={}", file.getFileId(), requesterId, isAdmin);
        return new CertContent(path, contentType, originName);
    }

    /**
     * 按 bizType 分级校验单文件大小。
     *
     * <p>servlet 上限（50MB）只是最后一道闸；非 CHAT 业务的 10MB 防线必须由这里守住，
     * 否则提额会连带放开资质证件 / 投诉证据等场景。bizType 解析不出来
     * （如打卡照片走裸串 {@code CHECKIN}，不在 {@link FileBizType} 白名单里）
     * 一律按默认 10MB 收紧。</p>
     */
    private static void checkSizeLimit(long size, String bizType) {
        boolean chat = FileBizType.of(bizType) == FileBizType.CHAT;
        long max = chat ? CHAT_MAX_BYTES : DEFAULT_MAX_BYTES;
        if (size > max) {
            // 段位纪律（AGENTS.md §2.7）：7xxx 属站内信与订单聊天，非聊天业务超限
            // 不得借用 CHAT_FILE_TOO_LARGE，否则头像 / 证件路径的错误码会带出聊天语义。
            throw new BusinessException(chat ? ResultCode.CHAT_FILE_TOO_LARGE : ResultCode.PARAM_ERROR,
                    chat ? "聊天附件超过 50MB 上限" : "文件超过大小上限（非聊天业务 10MB）");
        }
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
        // MP4: offset 4 起为 "ftyp"（ISO BMFF 容器，isom/mp42/MSNV 等 brand 全放行）
        if (bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
            return "mp4";
        }
        return null;
    }

    private static String clearable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
