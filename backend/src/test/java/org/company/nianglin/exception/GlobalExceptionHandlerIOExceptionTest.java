package org.company.nianglin.exception;

import org.company.nianglin.common.Result;
import org.company.nianglin.common.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 连接层异常处理单测（M8）。
 *
 * <p>这个分支很容易被当成「顺手加的一个 catch」而删掉，但它承担着一件具体的事：
 * <b>把「客户端断开」和「服务端真的写不出去」区分开</b>。</p>
 *
 * <p>背景：SSE 长连接的客户端关页面时，JVM 抛的是
 * {@code java.io.IOException: 你的主机中的软件中止了一个已建立的连接}，
 * 它经容器的 ERROR 分发回到本处理器。若落进兜底分支，会被记成 ERROR 级「系统异常」
 * 并试图写一个 {@code Result} —— 响应早已 committed，写必然失败，
 * Spring 与 Tomcat 各再抛一次，一次客户端断开变成三条 ERROR 堆栈。</p>
 *
 * <p>判据刻意用 {@code response.isCommitted()} 而非异常类型：只要响应已经提交，
 * 就不可能再写回结构化错误；反之必须照常报错，<b>不能把真正的 IO 故障静默掉</b>。
 * 下面两条用例就是这两个方向的护栏。</p>
 *
 * @author 银龄伴诊团队
 * @since M8
 */
@DisplayName("连接层异常：已提交不写回 / 未提交仍报错")
class GlobalExceptionHandlerIOExceptionTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("响应已提交（客户端断开）→ 不构造响应体，也不吞成「业务成功」")
    void committedResponseReturnsNothing() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/sse/message");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(true);

        Result<Void> result = handler.handleIOException(
                new IOException("你的主机中的软件中止了一个已建立的连接"), request, response);

        assertNull(result, "响应已提交时不能再写回任何响应体，否则会二次抛异常");
    }

    @Test
    @DisplayName("响应未提交（真实 IO 故障）→ 仍按系统异常返回，不掩盖问题")
    void uncommittedResponseStillReportsSystemError() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/order/1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setCommitted(false);

        Result<Void> result = handler.handleIOException(new IOException("磁盘写入失败"), request, response);

        assertNotNull(result, "真正的 IO 故障必须返回统一响应体");
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), result.getCode());
    }
}
