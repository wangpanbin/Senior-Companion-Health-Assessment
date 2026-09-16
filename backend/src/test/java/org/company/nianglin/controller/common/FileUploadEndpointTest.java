package org.company.nianglin.controller.common;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.constant.RoleConstants;
import org.company.nianglin.entity.SysFile;
import org.company.nianglin.mapper.SysFileMapper;
import org.company.nianglin.security.JwtTokenProvider;
import org.company.nianglin.security.TokenStore;
import org.company.nianglin.support.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 通用文件上传端点（{@code POST /api/file/upload}）的权限与类型校验矩阵。
 *
 * <p>与 {@code OrderAccessMatrixTest} / {@code ElderOwnershipMatrixTest} 同一套范式：
 * 真实 JWT 打真实接口、查真实种子库。</p>
 *
 * <p>⚠️ 成功用例会真的往 {@code sys_file} 落一行、往 {@code uploads/} 落一个文件；
 * 本类在 {@link #cleanup()} 里把二者都删掉，保证跑完种子数据原封不动。</p>
 *
 * @author 银龄伴诊团队
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("文件上传端点：权限与类型校验")
class FileUploadEndpointTest {

    private static final String AUTH = "Authorization";

    /** 最小合法 PNG：89 50 4E 47 0D 0A 1A 0A + 若干字节（>= MIN_HEADER_BYTES=12 即可） */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44
    };

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider tokenProvider;
    @Autowired
    private TokenStore tokenStore;
    @Autowired
    private SysFileMapper sysFileMapper;

    @Value("${nianglin.file.upload-dir:./uploads}")
    private String uploadDir;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 记录成功上传产生的资源，供 {@link #cleanup()} 清理，避免污染种子库与磁盘 */
    private String lastFileId;
    private String lastFileRelativePath;

    @AfterEach
    void cleanup() throws Exception {
        if (lastFileId != null) {
            sysFileMapper.delete(Wrappers.<SysFile>lambdaQuery().eq(SysFile::getFileId, lastFileId));
            lastFileId = null;
        }
        if (lastFileRelativePath != null) {
            Files.deleteIfExists(Paths.get(uploadDir, lastFileRelativePath));
            lastFileRelativePath = null;
        }
    }

    /** 签一张真实可用的 accessToken（密码版本取实时值，见 {@link TestTokens}）；已含 Bearer 前缀 */
    private String token(long userId, String role) {
        return TestTokens.bearer(tokenProvider, tokenStore, userId, role, "file");
    }

    private static MockMultipartFile pngFile(String name) {
        return new MockMultipartFile("file", name, MediaType.IMAGE_PNG_VALUE, PNG_BYTES);
    }

    @Test
    @DisplayName("权限 · 老人调用上传接口 → 403，提示只读（ElderReadOnlyInterceptor 默认拒绝写）")
    void elderUploadShouldBeForbidden() throws Exception {
        mockMvc.perform(multipart("/api/file/upload")
                        .file(pngFile("a.png"))
                        .param("bizType", "AVATAR")
                        .header(AUTH, token(201L, RoleConstants.ELDER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(containsString("只读")));
    }

    @Test
    @DisplayName("校验 · FAMILY 传非白名单 bizType(CHECKIN) → 参数错误，且不落库")
    void nonWhitelistedBizTypeShouldBeRejected() throws Exception {
        long before = sysFileMapper.selectCount(Wrappers.<SysFile>lambdaQuery());
        mockMvc.perform(multipart("/api/file/upload")
                        .file(pngFile("a.png"))
                        .param("bizType", "CHECKIN")
                        .header(AUTH, token(101L, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                // 错误提示里必须列出允许的取值，方便前端排查
                .andExpect(jsonPath("$.message").value(containsString("COMPANION_CERT")))
                .andExpect(jsonPath("$.message").value(containsString("AVATAR")));
        // 网关失配不能写脏数据：sys_file 行数不应增加
        assertEquals(before, (long) sysFileMapper.selectCount(Wrappers.<SysFile>lambdaQuery()));
    }

    @Test
    @DisplayName("校验 · 魔数非法的文件（txt 内容 hello） → 参数错误，提示文件类型不允许")
    void invalidMagicBytesShouldBeRejected() throws Exception {
        MockMultipartFile txt = new MockMultipartFile("file", "hello.txt",
                MediaType.TEXT_PLAIN_VALUE, "hello".getBytes());
        mockMvc.perform(multipart("/api/file/upload")
                        .file(txt)
                        .param("bizType", "AVATAR")
                        .header(AUTH, token(101L, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ResultCode.PARAM_ERROR.getCode()))
                .andExpect(jsonPath("$.message").value(containsString("文件类型不允许")));
    }

    @Test
    @DisplayName("成功 · FAMILY 传合法 PNG → 200，url 以 /uploads/ 开头，fileId 非空")
    void validPngUploadShouldSucceed() throws Exception {
        MvcResult result = mockMvc.perform(multipart("/api/file/upload")
                        .file(pngFile("avatar.png"))
                        .param("bizType", "AVATAR")
                        .header(AUTH, token(101L, RoleConstants.FAMILY)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.url").value(startsWith("/uploads/")))
                .andExpect(jsonPath("$.data.fileId").value(notNullValue()))
                .andReturn();

        // 记录刚落库的资源，交给 @AfterEach 清理
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        lastFileId = data.get("fileId").asText();
        String url = data.get("url").asText();
        lastFileRelativePath = url.startsWith("/uploads/") ? url.substring("/uploads/".length()) : null;
    }
}
