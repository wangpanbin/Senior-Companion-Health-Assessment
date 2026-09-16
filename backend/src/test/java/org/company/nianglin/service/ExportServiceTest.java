package org.company.nianglin.service;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import org.company.nianglin.common.ResultCode;
import org.company.nianglin.exception.BusinessException;
import org.company.nianglin.service.impl.ExportServiceImpl;
import org.company.nianglin.vo.UserExportVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 导出服务单测：响应头契约 / xlsx 真实性 / 异常分流。
 *
 * <h3>为什么这层也值得单测</h3>
 *
 * <p>{@link ExportServiceImpl} 只有 60 来行，容易被当成「没什么可测的胶水」。
 * 但它是 M10 导出链路的<b>唯一出口</b>，而它的三件事都只能在这里验：</p>
 *
 * <ol>
 *   <li><b>下载文件名</b>。文件名里带中文（{@code 用户数据_20260916_101500.xlsx}），
 *       只写 {@code filename=} 会让部分浏览器退化成「下载」这种无意义的文件名；
 *       本测试锁死「ASCII 回退 + RFC 5987 {@code filename*} 双写」这一实现。</li>
 *   <li><b>写出来的到底是不是 xlsx</b>。EasyExcel 把 {@code rows} 序列化成 OOXML（zip 容器），
 *       因此响应体的前两字节必须是 {@code PK}。这条断言能挡住「写成了 CSV / 写了个空流」
 *       这类不会抛异常、只会让用户下到一个坏文件的故障。</li>
 *   <li><b>两类失败必须分流</b>。判据是<b>响应有没有提交</b>，而不是异常类型：
 *       实测 EasyExcel 在 {@code finish()} 阶段会用自己包装的异常顶掉原始异常
 *       （消息固定为 {@code Can not close IO}，cause 是关闭输出流时的 IOException），
 *       原始异常类型在那一刻已经丢失，「按类型分流」必然分错。
 *       响应已提交 ⇒ 客户端多半是取消了下载，回不了 JSON 错误体，只能记 WARN；
 *       一个字节都没发出去 ⇒ 是真正的生成故障，必须转 {@code BusinessException} 让前端能弹提示。
 *       这条判据与 {@code GlobalExceptionHandler} 对 IOException 的处理完全一致。</li>
 * </ol>
 *
 * @author 银龄伴诊团队
 * @since M12
 */
@DisplayName("导出服务：响应头契约 / xlsx 真实性 / 异常分流")
class ExportServiceTest {

    private static final String CONTENT_TYPE_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private ExportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ExportServiceImpl();
    }

    /* ================================================================== */
    /* 1 · 正常写出                                                         */
    /* ================================================================== */

    @Test
    @DisplayName("导出 · 响应头：xlsx MIME + attachment + 中文文件名双写（ASCII 回退 + RFC 5987）")
    void shouldWriteXlsxHeadersWithEncodedChineseFileName() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.writeExcel(response, "用户数据", UserExportVO.class, List.of(row()), "用户列表");

        // setCharacterEncoding 会让容器把 charset 拼到 content-type 后面（Tomcat 同样如此），
        // 因此只断言 MIME 前缀，不锁完整字符串
        assertTrue(response.getContentType().startsWith(CONTENT_TYPE_XLSX), response.getContentType());
        assertEquals(StandardCharsets.UTF_8.name(), response.getCharacterEncoding());

        String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertNotNull(disposition);
        assertTrue(disposition.startsWith("attachment; "), "必须是附件下载而不是内联展示");
        // ASCII 回退：老浏览器不认识 filename* 时至少能拿到一个可用名字
        assertTrue(disposition.contains("filename=\"export.xlsx\""), disposition);
        assertTrue(disposition.contains("filename*=UTF-8''"), disposition);

        // 中文文件名必须编码后才合法，且不能出现「+ 代替空格」的老问题
        String encoded = disposition.substring(disposition.indexOf("filename*=UTF-8''")
                + "filename*=UTF-8''".length());
        String decoded = URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8);
        assertTrue(decoded.startsWith("用户数据_"), decoded);
        assertTrue(decoded.endsWith(".xlsx"), decoded);
        assertTrue(encoded.matches("[\\x20-\\x7E]+"), "响应头只能是 ASCII：" + encoded);
    }

    @Test
    @DisplayName("导出 · 响应体真的是 xlsx（zip 容器，前两字节 PK）而不是空流")
    void shouldProduceRealXlsxBody() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.writeExcel(response, "用户数据", UserExportVO.class, List.of(row()), "用户列表");

        byte[] body = response.getContentAsByteArray();
        assertTrue(body.length > 0, "响应体不能为空");
        assertEquals('P', body[0]);
        assertEquals('K', body[1]);
    }

    @Test
    @DisplayName("导出 · rows 传 null 不抛异常（导出空表头也是合法结果）")
    void shouldTolerateNullRows() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertDoesNotThrow(() -> service.writeExcel(response, "用户数据", UserExportVO.class, null, "用户列表"));

        assertTrue(response.getContentAsByteArray().length > 0);
    }

    /* ================================================================== */
    /* 2 · 异常分流                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("导出 · 下载中途断开（响应在写入时提交）→ 只记 WARN，不抛异常")
    void committedFailureShouldNotPropagate() {
        // 客户端取消下载时，第一个字节发出去的那一刻响应就提交了 —— 这正是判定
        // 「回不了 JSON 错误体」的依据。实测 EasyExcel 会把原始异常替换成自己的
        // Can not close IO（cause 才是 IOException），所以只能靠响应状态区分
        MockHttpServletResponse response = disconnectingResponse(new IOException("Broken pipe"));

        assertDoesNotThrow(() -> service.writeExcel(response, "用户数据", UserExportVO.class,
                List.of(row()), "用户列表"));
    }

    @Test
    @DisplayName("导出 · 一个字节都没写出去就失败 → 500 SYSTEM_ERROR，前端能弹提示")
    void uncommittedFailureShouldBecomeBusinessException() {
        MockHttpServletResponse response = brokenResponse(new RuntimeException("template broken"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.writeExcel(response, "用户数据", UserExportVO.class, List.of(row()), "用户列表"));

        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("导出 · 进来时响应就已提交 → 直接放弃，不碰 reset()（否则 reset 抛的 IllegalStateException 会逃出本方法）")
    void alreadyCommittedResponseShouldBeAbandoned() {
        MockHttpServletResponse response = brokenResponse(new RuntimeException("不应该被触发"));
        response.setCommitted(true);

        assertDoesNotThrow(() -> service.writeExcel(response, "用户数据", UserExportVO.class,
                List.of(row()), "用户列表"));

        assertEquals(0, response.getContentAsByteArray().length, "已经放弃导出，就不能再写任何字节");
    }

    /* ================================================================== */

    /**
     * 取流即失败，且<b>取流那一刻响应就提交</b>：模拟「下载下到一半客户端没了」。
     *
     * <p>这个细节很关键，它把两种失败真正分开：进来时未提交（可以回 JSON 错误体）
     * 与写到一半才提交（回不了，只能记日志）。</p>
     */
    private MockHttpServletResponse disconnectingResponse(Throwable failure) {
        return new MockHttpServletResponse() {
            @Override
            public ServletOutputStream getOutputStream() {
                setCommitted(true);
                return failingStream(failure);
            }
        };
    }

    /**
     * 取流即失败：用来分别模拟「客户端断开」与「生成失败」。
     *
     * <p>{@code getOutputStream()} 是 EasyExcel 真正落笔的地方，
     * 在这里抛异常等价于「写到一半通道没了」，不需要真的起一个 HTTP 服务。</p>
     *
     * <p>参数是 {@link Throwable} 而非 {@link RuntimeException}：{@code ServletOutputStream.write}
     * 本身不声明受检异常，要在这里抛出一个 {@code IOException} 得走
     * {@link #sneakyThrow} —— 这正是生产代码里「客户端断开会以 IOException 形式冒出来」的由来，
     * 用 try/catch 包装反而会把异常类型换掉，测不到真实分支。</p>
     */
    private MockHttpServletResponse brokenResponse(Throwable failure) {
        return new MockHttpServletResponse() {
            @Override
            public ServletOutputStream getOutputStream() {
                return failingStream(failure);
            }
        };
    }

    /** 写任何字节都抛给定异常的输出流 */
    private static ServletOutputStream failingStream(Throwable failure) {
        return new ServletOutputStream() {
            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setWriteListener(WriteListener listener) {
                // 同步写，不需要回调
            }

            @Override
            public void write(int b) {
                ExportServiceTest.<RuntimeException>sneakyThrow(failure);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                ExportServiceTest.<RuntimeException>sneakyThrow(failure);
            }
        };
    }

    /** 原样抛出给定异常，绕过编译器对受检异常的检查（仅测试用） */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
        throw (T) t;
    }

    /** 用真实导出 VO，顺便保证它确实能被 EasyExcel 序列化（字段注解写错会在这里炸） */
    private UserExportVO row() {
        UserExportVO vo = new UserExportVO();
        vo.setId(101L);
        vo.setUsername("family101");
        vo.setNickname("王*");
        // 脱敏串，不是完整手机号 —— 导出 VO 的字段语义由 Service 保证
        vo.setPhone("138****8888");
        vo.setRoleLabel("家属");
        vo.setStatusLabel("正常");
        vo.setOrderCount(3);
        vo.setCreateTime("2026-08-01 09:00:00");
        vo.setLastLoginTime("2026-09-16 08:30:00");
        return vo;
    }

    /** 供将来断言「响应体里没有明文手机号」时复用 */
    @SuppressWarnings("unused")
    private byte[] bodyOf(MockHttpServletResponse response) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.writeBytes(response.getContentAsByteArray());
        return buffer.toByteArray();
    }
}
