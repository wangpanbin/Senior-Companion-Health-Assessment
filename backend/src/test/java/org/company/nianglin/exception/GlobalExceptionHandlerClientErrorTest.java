package org.company.nianglin.exception;

import org.company.nianglin.common.Result;
import org.company.nianglin.common.ResultCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端错误的归类单测：**调用方犯错 ≠ 服务端故障**。
 *
 * <h3>为什么值得单独立类</h3>
 *
 * <p>{@link GlobalExceptionHandler} 最后有一个 {@code @ExceptionHandler(Exception.class)} 兜底，
 * 它把所有漏网的异常统一转成 {@code SYSTEM_ERROR}(500) 并记 <b>ERROR</b> 级日志。
 * 这个兜底必须存在，但它也是一块「吸铁石」：任何新出现的客户端错误——少传一个 part、
 * 文件超限、URL 拼错——只要没人单独接住，就会掉进去，于是</p>
 *
 * <ul>
 *   <li>日志里刷 ERROR 堆栈，监控看起来像服务在崩；</li>
 *   <li>返回 {@code code=500}，用户看到「服务器开小差了」，而真正原因是自己的请求不对，
 *       于是他只会原样重试。</li>
 * </ul>
 *
 * <p>本类逐条锁死「这些异常必须落在客户端错误码上」，同时保留一条反向用例：
 * 真正的程序缺陷（NPE 之类）仍必须走 500，<b>不能为了消灭日志噪声把兜底掏空</b>。</p>
 *
 * <p>另外这里也顺带固化了那条最容易误判的时序：{@code MissingServletRequestPartException}
 * 在<b>参数解析阶段</b>抛出，早于方法级 {@code @PreAuthorize} ——
 * 越权矩阵若用「不带文件的上传请求」断言 403，会拿到 200 而误以为鉴权失效。</p>
 *
 * @author 银龄伴诊团队
 * @since M12
 */
@DisplayName("异常归类：客户端错误不进兜底 / 真缺陷仍返回 500")
class GlobalExceptionHandlerClientErrorTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static final MockHttpServletRequest REQUEST =
            new MockHttpServletRequest("POST", "/api/execution/1001/photo");

    /* ================================================================== */
    /* 1 · 上传相关：缺文件 / 文件超限                                       */
    /* ================================================================== */

    @Test
    @DisplayName("上传 · 缺 multipart 文件部分 → PARAM_ERROR（不是 500，也早于 @PreAuthorize）")
    void missingPartShouldBeParamError() {
        Result<Void> result = handler.handleMissingPart(
                new MissingServletRequestPartException("file"),
                new MockHttpServletRequest("POST", "/api/execution/1001/photo"));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("file"), result.getMessage());
    }

    @Test
    @DisplayName("上传 · 文件超过 10 MB → PARAM_ERROR 且提示「重新挑选文件」的信息量")
    void maxUploadSizeShouldBeParamError() {
        Result<Void> result = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(10 * 1024 * 1024),
                new MockHttpServletRequest("POST", "/api/execution/1001/photo"));

        assertEquals(ResultCode.PARAM_ERROR.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("10 MB"), result.getMessage());
    }

    /* ================================================================== */
    /* 2 · 请求本身的毛病                                                    */
    /* ================================================================== */

    @Test
    @DisplayName("请求体 · JSON 解析不了 → PARAM_ERROR")
    void unreadableBodyShouldBeParamError() {
        Result<Void> result = handler.handleMessageNotReadable(
                new org.springframework.http.converter.HttpMessageNotReadableException(
                        "not json", new MockHttpInputMessage("{".getBytes(StandardCharsets.UTF_8))),
                REQUEST);

        assertEquals(ResultCode.PARAM_ERROR.getCode(), result.getCode());
    }

    /**
     * 见 {@code reports/playwright/e2e-report.md §F-02}：
     * {@code POST /api/order/{id}/complete} 要求 {@code @RequestBody}（仅接受 JSON），
     * 客户端用 {@code application/x-www-form-urlencoded} 缺体调用时，
     * 旧实现会落进 {@code @ExceptionHandler(Exception.class)} 兜底 → code=500 + ERROR 日志，
     * 而真"空 body + JSON 头"会被 {@link org.springframework.web.bind.MethodArgumentNotValidException} 接住。
     * 两条相邻路径同请求只差一个头，返回却不同，违反"客户端错误应统一可预期"的原则。
     * 此用例锁死统一为 PARAM_ERROR。
     */
    @Test
    @DisplayName("请求体 · Content-Type 不被 @RequestBody 接受（如 form-urlencoded）→ PARAM_ERROR（不再走兜底 500）")
    void unsupportedMediaTypeShouldBeParamError() {
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException(
                MediaType.APPLICATION_FORM_URLENCODED,
                java.util.List.of(MediaType.APPLICATION_JSON));

        Result<Void> result = handler.handleMediaTypeNotSupported(ex, REQUEST);

        assertEquals(ResultCode.PARAM_ERROR.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("application/json"),
                "提示语应引导客户端改用 JSON 头：" + result.getMessage());
    }

    /**
     * 边界：客户端发了一个"合法但非 JSON 也不在白名单"的 Content-Type（如 {@code text/xml}），
     * 提示语要把这个实际收到的 type 反映出来，方便排障，而不是吞掉。
     */
    @Test
    @DisplayName("请求体 · Content-Type 是 text/xml（非 JSON 也不白名单）→ PARAM_ERROR 且提示中包含 text/xml")
    void unsupportedMediaTypeWithNonJsonTypeShouldMentionIt() {
        HttpMediaTypeNotSupportedException ex = new HttpMediaTypeNotSupportedException(
                MediaType.APPLICATION_XML,
                java.util.List.of(MediaType.APPLICATION_JSON));

        Result<Void> result = handler.handleMediaTypeNotSupported(ex, REQUEST);

        assertEquals(ResultCode.PARAM_ERROR.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("application/xml"),
                "提示语应包含实际收到的 Content-Type：" + result.getMessage());
    }

    @Test
    @DisplayName("请求体 · @Valid 校验失败 → PARAM_ERROR，且把字段级提示拼成一句人话")
    void validationFailureShouldAggregateFieldMessages() {
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "userDisableDTO");
        binding.addError(new FieldError("userDisableDTO", "reason", "请填写封禁原因"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter(), binding);

        Result<Void> result = handler.handleMethodArgumentNotValid(ex, REQUEST);

        assertEquals(ResultCode.PARAM_ERROR.getCode(), result.getCode());
        assertEquals("请填写封禁原因", result.getMessage());
    }

    @Test
    @DisplayName("查询参数 · 缺少必填参数 → PARAM_ERROR 且指出参数名")
    void missingQueryParamShouldBeParamError() {
        Result<Void> result = handler.handleMissingParameter(
                new MissingServletRequestParameterException("startDate", "String"), REQUEST);

        assertEquals(ResultCode.PARAM_ERROR.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("startDate"), result.getMessage());
    }

    @Test
    @DisplayName("路径变量 · 类型不匹配（/user/abc/disable）→ PARAM_ERROR 而不是 500")
    void typeMismatchShouldBeParamError() throws Exception {
        MethodArgumentTypeMismatchException ex = new MethodArgumentTypeMismatchException(
                "abc", Long.class, "id", parameter(), new IllegalArgumentException("NumberFormatException"));

        Result<Void> result = handler.handleTypeMismatch(ex, REQUEST);

        assertEquals(ResultCode.PARAM_ERROR.getCode(), result.getCode());
        assertTrue(result.getMessage().contains("id"), result.getMessage());
    }

    @Test
    @DisplayName("方法 · 用错 HTTP 动词 → METHOD_NOT_ALLOWED 而不是 500")
    void wrongHttpMethodShouldBeMethodNotAllowed() {
        Result<Void> result = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("DELETE"), REQUEST);

        assertEquals(ResultCode.METHOD_NOT_ALLOWED.getCode(), result.getCode());
    }

    /* ================================================================== */
    /* 3 · 路由不存在                                                        */
    /* ================================================================== */

    @Test
    @DisplayName("路由 · URL 拼错 → NOT_FOUND，且不记成「系统故障」")
    void unknownPathShouldBeNotFound() {
        Result<Void> result = handler.handleNoResourceFound(
                new NoResourceFoundException(HttpMethod.GET, "api/sse/messag"), REQUEST);

        assertEquals(ResultCode.NOT_FOUND.getCode(), result.getCode());
    }

    /* ================================================================== */
    /* 4 · 反向用例：兜底不能因为「分类变细」而被掏空                            */
    /* ================================================================== */

    @Test
    @DisplayName("兜底 · 真程序缺陷（IllegalStateException）仍必须是 500 SYSTEM_ERROR")
    void realDefectShouldStillBeSystemError() {
        Result<Void> result = handler.handleException(new IllegalStateException("空指针外溢"), REQUEST);

        assertNotNull(result);
        assertEquals(ResultCode.SYSTEM_ERROR.getCode(), result.getCode(),
                "把客户端错误分门别类，不等于把真故障也伪装成参数问题");
    }

    /** 构造一个方法参数用于参数校验 / 类型不匹配异常，与具体业务无关 */
    private static MethodParameter parameter() {
        try {
            return new MethodParameter(String.class.getDeclaredMethod("substring", int.class), 0);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }
}
