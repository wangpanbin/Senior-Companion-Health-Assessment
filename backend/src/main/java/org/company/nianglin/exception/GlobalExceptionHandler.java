package org.company.nianglin.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.company.nianglin.common.Result;
import org.company.nianglin.common.ResultCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.stream.Collectors;

/**
 * 全局异常处理器。
 *
 * <p>目标：无论后端出现何种异常，前端拿到的永远是结构一致的 {@link Result}，
 * 绝不出现白页或 HTML 错误堆栈。</p>
 *
 * <p>⚠️ 日志只记录必要的上下文（请求路径、方法），禁止打印请求体原文，
 * 避免身份证号、密码等敏感信息落盘。</p>
 *
 * @author 银龄伴诊团队
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /* ==================== 业务异常 ==================== */

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Void>> handleBusinessException(BusinessException e, HttpServletRequest request) {
        log.warn("业务异常 | {} {} | code={} | message={}", request.getMethod(), request.getRequestURI(), e.getCode(), e.getMessage());
        return ResponseEntity.status(httpStatusOf(e.getCode())).body(Result.fail(e.getCode(), e.getMessage()));
    }

    /**
     * 业务码 → HTTP 状态码。
     *
     * <p>约定（{@code docs/api/README.md} §3.2）：业务错误一律 HTTP 200，由 {@code code} 区分。
     * 但 **401 与 403 例外** —— 它们表达的是「HTTP 层就没通过」，前端拦截器要靠状态码
     * 决定「去刷新令牌」还是「提示无权限」。若也返回 200，前端只能靠 body 猜，容易漏判。</p>
     */
    private static HttpStatus httpStatusOf(Integer code) {
        if (ResultCode.UNAUTHORIZED.getCode().equals(code)) {
            return HttpStatus.UNAUTHORIZED;
        }
        if (ResultCode.FORBIDDEN.getCode().equals(code)) {
            return HttpStatus.FORBIDDEN;
        }
        return HttpStatus.OK;
    }

    /* ==================== 参数校验 ==================== */

    /** @RequestBody 上的 @Valid 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleMethodArgumentNotValid(MethodArgumentNotValidException e, HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数校验失败 | {} {} | {}", request.getMethod(), request.getRequestURI(), message);
        return Result.fail(ResultCode.PARAM_ERROR, message);
    }

    /** 表单 / Query 对象绑定校验失败 */
    @ExceptionHandler(BindException.class)
    public Result<Void> handleBindException(BindException e, HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数绑定失败 | {} {} | {}", request.getMethod(), request.getRequestURI(), message);
        return Result.fail(ResultCode.PARAM_ERROR, message);
    }

    /** 方法级 @Validated 校验失败（路径变量 / 请求参数） */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e, HttpServletRequest request) {
        String message = e.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.joining("；"));
        log.warn("参数约束失败 | {} {} | {}", request.getMethod(), request.getRequestURI(), message);
        return Result.fail(ResultCode.PARAM_ERROR, message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParameter(MissingServletRequestParameterException e, HttpServletRequest request) {
        log.warn("缺少必填参数 | {} {} | {}", request.getMethod(), request.getRequestURI(), e.getParameterName());
        return Result.fail(ResultCode.PARAM_ERROR, "缺少必填参数：" + e.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        log.warn("参数类型不匹配 | {} {} | {}", request.getMethod(), request.getRequestURI(), e.getName());
        return Result.fail(ResultCode.PARAM_ERROR, "参数格式不正确：" + e.getName());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleMessageNotReadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        log.warn("请求体解析失败 | {} {} | {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return Result.fail(ResultCode.PARAM_ERROR, "请求体格式不正确");
    }

    /* ==================== 认证与鉴权 ==================== */

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Result<Void>> handleAuthentication(AuthenticationException e, HttpServletRequest request) {
        log.warn("认证失败 | {} {} | {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Result.fail(ResultCode.UNAUTHORIZED));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> handleAccessDenied(AccessDeniedException e, HttpServletRequest request) {
        log.warn("越权访问被拦截 | {} {} | {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Result.fail(ResultCode.FORBIDDEN));
    }

    /* ==================== 路由与请求 ==================== */

    @ExceptionHandler(NoHandlerFoundException.class)
    public Result<Void> handleNoHandlerFound(NoHandlerFoundException e, HttpServletRequest request) {
        log.warn("接口不存在 | {} {}", request.getMethod(), request.getRequestURI());
        return Result.fail(ResultCode.NOT_FOUND);
    }

    /**
     * 静态资源未命中（Spring 6.1+）。
     *
     * <p>这是<b>另一个</b>异常，容易和 {@link NoHandlerFoundException} 混淆。
     * 请求一个不存在的路径时，Spring MVC 在没有匹配的 {@code @RequestMapping} 之后
     * 会退到静态资源处理器，由它抛出 {@code NoResourceFoundException}
     * （消息形如 {@code No static resource api/sse/message}）。
     * 不单独处理的话它会落进兜底分支，把「调用方把 URL 拼错了」记成 ERROR 级系统故障 ——
     * 监控上看着像服务崩了，排查时却在代码里找不到对应逻辑。</p>
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public Result<Void> handleNoResourceFound(NoResourceFoundException e, HttpServletRequest request) {
        log.warn("接口不存在 | {} {}", request.getMethod(), request.getRequestURI());
        return Result.fail(ResultCode.NOT_FOUND);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        log.warn("请求方法不支持 | {} {} | {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return Result.fail(ResultCode.METHOD_NOT_ALLOWED);
    }

    /* ==================== 上传 ==================== */

    /**
     * 上传文件超过 {@code spring.servlet.multipart.max-file-size}。
     *
     * <p>不处理的话会落到兜底分支变成 500，用户看到「服务器开小差了」，
     * 而真实原因是「文件太大」—— 他只会换个文件继续试，一直试不出来。
     * 大小限制写在配置里而不是代码里，因此这里只做翻译，不重复写死阈值。</p>
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleMaxUploadSize(MaxUploadSizeExceededException e, HttpServletRequest request) {
        log.warn("上传文件超过限制 | {} {}", request.getMethod(), request.getRequestURI());
        return Result.fail(ResultCode.PARAM_ERROR, "文件超过 10 MB 上限，请压缩后重试");
    }

    /* ==================== 数据完整性 ==================== */

    /**
     * 唯一索引冲突。
     *
     * <p>业务代码都会先查一次再插入（为了给人话提示），所以正常路径下不会走到这里。
     * 能走到这里说明是<b>并发穿透</b>：两个请求同时通过了「不存在」的检查。
     * 此时数据库是最后一道防线，回 409 而不是 500 ——
     * 对用户来说「已经有人提交过了」和「服务器故障」是完全不同的两件事。</p>
     */
    @ExceptionHandler(DuplicateKeyException.class)
    public Result<Void> handleDuplicateKey(DuplicateKeyException e, HttpServletRequest request) {
        log.warn("唯一约束冲突 | {} {} | {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return Result.fail(ResultCode.CONFLICT, "该记录已存在或已被处理，请刷新后重试");
    }

    /* ==================== 连接层异常 ==================== */

    /**
     * 客户端在响应写完之前断开连接。
     *
     * <p>最典型的场景是 SSE 长连接（{@code GET /sse/message}）：用户关页面、切网络、
     * 被 NAT/代理掐断，服务端<b>下一次写</b>时才会发现，抛出的就是
     * {@code java.io.IOException: 你的主机中的软件中止了一个已建立的连接}。
     * Spring 自己抛的
     * {@link org.springframework.web.context.request.async.AsyncRequestNotUsableException}
     * 也继承自 {@code IOException}，因此这里一并覆盖（实测两者都会经容器的 ERROR 分发回到本处理器）。</p>
     *
     * <p><b>为什么不能走兜底分支</b>：兜底会把它记成 ERROR 级「系统异常」并试图写一个
     * {@code Result} 响应体 —— 但响应早就 committed，写必然失败，于是 Spring 与 Tomcat
     * 各自再抛一次，一次客户端断开在日志里变成三条 ERROR 堆栈。
     * 结果运维看到「服务在报系统异常」，而真相只是有人关了浏览器，
     * 真正的故障反而被淹没。</p>
     *
     * <p>判据用 {@code response.isCommitted()} 而不是异常类型：<b>只要响应已经提交，
     * 任何异常都不可能再写回一个结构化错误</b>，此时记 DEBUG 并放弃写回是唯一正确的选择；
     * 反之说明是真正的 IO 故障（磁盘、序列化等），仍旧按 ERROR 记录并返回统一响应体，
     * 不掩盖问题。</p>
     */
    @ExceptionHandler(IOException.class)
    public Result<Void> handleIOException(IOException e, HttpServletRequest request,
                                          HttpServletResponse response) {
        if (response.isCommitted()) {
            log.debug("客户端已断开，响应已提交，无可写回 | {} {} | {}",
                    request.getMethod(), request.getRequestURI(), e.getMessage());
            return null;
        }
        log.error("响应写入失败 | {} {}", request.getMethod(), request.getRequestURI(), e);
        return Result.fail(ResultCode.SYSTEM_ERROR);
    }

    /* ==================== 兜底 ==================== */

    @ExceptionHandler(Exception.class)
    public Result<Void> handleException(Exception e, HttpServletRequest request) {
        log.error("系统异常 | {} {}", request.getMethod(), request.getRequestURI(), e);
        return Result.fail(ResultCode.SYSTEM_ERROR);
    }
}
