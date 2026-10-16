package org.company.nianglin.service.impl;

import org.company.nianglin.common.ResultCode;
import org.company.nianglin.entity.SysFile;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.mapper.SysFileMapper;
import org.company.nianglin.vo.FileUploadVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件存储的 M13 增量行为（ADR-0011）：mp4 魔数判型、按 bizType 分级限额、流式落盘。
 *
 * <p>纯 Mockito 单测，不依赖数据库与 Spring 上下文；端到端的上传权限矩阵
 * 见 {@code FileUploadEndpointTest}（真实 JWT 打真实接口）。</p>
 *
 * @author 银龄伴诊团队
 * @since M13
 */
@DisplayName("文件存储：mp4 判型 / 分级限额 / 流式落盘")
class FileStorageChatTest {

    /** 最小合法 mp4 头：4 字节任意 + "ftyp" + brand "isom"（凑满 12 字节判型窗口） */
    private static final byte[] MP4_BYTES = {
            0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm',
            0x00, 0x00, 0x02, 0x00
    };

    /** 最小合法 PNG（与 FileUploadEndpointTest 同一份数据） */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44
    };

    private static final long CHAT_LIMIT = 50L * 1024 * 1024;
    private static final long DEFAULT_LIMIT = 10L * 1024 * 1024;

    private final SysFileMapper sysFileMapper = mock(SysFileMapper.class);
    private FileStorageServiceImpl service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = new FileStorageServiceImpl(sysFileMapper);
        ReflectionTestUtils.setField(service, "uploadDir", tempDir.toString());
    }

    // ---------- mp4 魔数判型 ----------

    @Test
    @DisplayName("判型 · mp4 文件头（offset 4 起为 ftyp）→ 识别为 mp4")
    void detectExtensionShouldRecognizeMp4() {
        assertEquals("mp4", FileStorageServiceImpl.detectExtension(MP4_BYTES));
    }

    @Test
    @DisplayName("判型 · 改名伪装的 PNG（物理证据优先）→ 仍识别为 png，不因扩展名放行")
    void detectExtensionShouldTrustMagicBytesOverExtension() {
        assertEquals("png", FileStorageServiceImpl.detectExtension(PNG_BYTES));
    }

    @Test
    @DisplayName("判型 · 不足 12 字节的文件头 → 无法识别")
    void detectExtensionShouldRejectShortHeader() {
        assertEquals(null, FileStorageServiceImpl.detectExtension("short".getBytes(StandardCharsets.UTF_8)));
    }

    // ---------- 分级限额 ----------

    @Test
    @DisplayName("限额 · CHAT 类型 50MB+1 字节 → 7009，且不触发任何落盘")
    void chatUploadOverLimitShouldReport7009() {
        MultipartFile file = fileOf(CHAT_LIMIT + 1);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.store(file, "CHAT", null, 101L));
        assertEquals(ResultCode.CHAT_FILE_TOO_LARGE.getCode(), ex.getCode());
        verify(sysFileMapper, never()).insert(any(SysFile.class));
    }

    @Test
    @DisplayName("限额 · 非聊天业务（COMPLAINT）10MB+1 字节 → 7009（10MB 防线未被提额冲垮）")
    void nonChatUploadOverTenMbShouldReport7009() {
        MultipartFile file = fileOf(DEFAULT_LIMIT + 1);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.store(file, "COMPLAINT", null, 101L));
        assertEquals(ResultCode.CHAT_FILE_TOO_LARGE.getCode(), ex.getCode());
        verify(sysFileMapper, never()).insert(any(SysFile.class));
    }

    @Test
    @DisplayName("限额 · 白名单外裸串（CHECKIN）超 10MB → 7009（解析不出按默认收紧）")
    void unknownBizTypeOverTenMbShouldReport7009() {
        MultipartFile file = fileOf(DEFAULT_LIMIT + 1);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.store(file, "CHECKIN", 1001L, 301L));
        assertEquals(ResultCode.CHAT_FILE_TOO_LARGE.getCode(), ex.getCode());
    }

    // ---------- 流式落盘 ----------

    @Test
    @DisplayName("落盘 · CHAT 类型合法 mp4（12MB 逻辑上限内，测试用 16 字节实体）→ 落盘成功且 ext=mp4")
    void chatUploadWithinLimitShouldStreamToDisk() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "clip.mp4", "video/mp4", MP4_BYTES);

        FileUploadVO vo = service.store(file, "CHAT", null, 101L);

        assertNotNull(vo.getFileId());
        assertTrue(vo.getUrl().endsWith(".mp4"));
        assertTrue(vo.getUrl().startsWith("/uploads/"));
        // 流式拷贝的完整性：磁盘上的字节数与上传字节数一致
        Path stored = tempDir.resolve(vo.getUrl().substring("/uploads/".length()));
        assertTrue(Files.exists(stored));
        assertEquals(MP4_BYTES.length, Files.size(stored));

        ArgumentCaptor<SysFile> captor = ArgumentCaptor.forClass(SysFile.class);
        verify(sysFileMapper).insert(captor.capture());
        assertEquals("CHAT", captor.getValue().getBizType());
        assertEquals("mp4", captor.getValue().getExt());
        assertEquals(101L, captor.getValue().getUploaderId());
    }

    @Test
    @DisplayName("落盘 · CHAT 类型传 mp4 扩展名的 PNG（改名伪装）→ 按魔数存为 .jpg/png 物理扩展")
    void chatUploadRenamedPngShouldStoreByRealType() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.mp4", "video/mp4", PNG_BYTES);

        FileUploadVO vo = service.store(file, "CHAT", null, 101L);

        assertTrue(vo.getUrl().endsWith(".png"));
        ArgumentCaptor<SysFile> captor = ArgumentCaptor.forClass(SysFile.class);
        verify(sysFileMapper).insert(captor.capture());
        assertEquals("png", captor.getValue().getExt());
    }

    @Test
    @DisplayName("落盘 · 空文件 → 参数错误，不落库")
    void emptyFileShouldBeRejected() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.store(new MockMultipartFile("file", "a.png", MediaType.IMAGE_PNG_VALUE, new byte[0]),
                        "CHAT", null, 101L));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(sysFileMapper, never()).insert(any(SysFile.class));
    }

    @Test
    @DisplayName("非聊天业务不可上传mp4")
    void nonChatUploadShouldRejectMp4() {
        MockMultipartFile file = new MockMultipartFile("file", "clip.mp4", "video/mp4", MP4_BYTES);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.store(file, "COMPLAINT", null, 101L));
        assertEquals(ResultCode.PARAM_ERROR.getCode(), ex.getCode());
        verify(sysFileMapper, never()).insert(any(SysFile.class));
    }

    @Test
    @DisplayName("正好50MB的聊天文件可完整流式落盘")
    void chatUploadShouldAcceptExactFiftyMb() throws Exception {
        MultipartFile file = fileOf(CHAT_LIMIT);
        when(file.getInputStream()).thenAnswer(invocation -> new java.io.SequenceInputStream(
                new java.io.ByteArrayInputStream(MP4_BYTES),
                new java.io.InputStream() {
                    private long remaining = CHAT_LIMIT - MP4_BYTES.length;
                    @Override public int read() { return remaining-- > 0 ? 0 : -1; }
                    @Override public int read(byte[] buffer, int offset, int length) {
                        if (remaining <= 0) return -1;
                        int count = (int) Math.min(length, remaining);
                        java.util.Arrays.fill(buffer, offset, offset + count, (byte) 0);
                        remaining -= count;
                        return count;
                    }
                }));
        FileUploadVO vo = service.store(file, "CHAT", null, 101L);
        assertEquals(CHAT_LIMIT, Files.size(tempDir.resolve(vo.getUrl().substring("/uploads/".length()))));
        verify(file, never()).getBytes();
    }

    /** 只stub大小与判空的大文件替身：限额校验发生在读流之前，getInputStream 不应被触达 */
    private static MultipartFile fileOf(long size) {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(size);
        return file;
    }
}
