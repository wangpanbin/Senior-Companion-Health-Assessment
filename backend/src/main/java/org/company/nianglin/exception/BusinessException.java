package org.company.nianglin.exception;

import lombok.Getter;
import org.company.nianglin.common.ResultCode;

import java.io.Serial;

/**
 * 业务异常。
 *
 * <p>业务校验失败时抛出本异常，由 {@link GlobalExceptionHandler} 统一转换为
 * {@code Result} 结构返回。</p>
 *
 * <p><b>HTTP 状态码规则</b>：默认 200，业务语义由 {@code code} 表达；
 * 但 {@link ResultCode#UNAUTHORIZED}（401）与 {@link ResultCode#FORBIDDEN}（403）会
 * 被映射为对应的 HTTP 状态码 —— 前端拦截器要靠 HTTP 状态码决定「去刷新令牌」还是
 * 「提示无权限」。如果也返回 200，前端只能靠 body 猜，容易漏判。
 * 详见 {@code GlobalExceptionHandler#httpStatusOf}。</p>
 *
 * <p>用法：{@code throw new BusinessException(ResultCode.ORDER_ALREADY_TAKEN);}</p>
 *
 * @author 银龄伴诊团队
 */
@Getter
public class BusinessException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务响应码 */
    private final Integer code;

    /**
     * 随错误返回的业务数据（V4 新增），绝大多数错误为 {@code null}。
     *
     * <p>首个用例是 {@code 11001}（注销时存在进行中订单）：{@code data} 携带
     * {@code { "pendingOrderCount": n }}，前端据此如实提示「还有几单没走完」。
     * 仅当错误<b>必须</b>附带机器可读的上下文时才使用本字段 —— 能写进
     * {@code message} 的不放这里，避免两个出口漂移。</p>
     */
    private final transient Object data;

    public BusinessException(ResultCode resultCode) {
        super(resultCode.getMessage());
        this.code = resultCode.getCode();
        this.data = null;
    }

    public BusinessException(ResultCode resultCode, String message) {
        super(message);
        this.code = resultCode.getCode();
        this.data = null;
    }

    public BusinessException(ResultCode resultCode, String message, Object data) {
        super(message);
        this.code = resultCode.getCode();
        this.data = data;
    }

    public BusinessException(Integer code, String message) {
        super(message);
        this.code = code;
        this.data = null;
    }

    public BusinessException(String message) {
        super(message);
        this.code = ResultCode.SYSTEM_ERROR.getCode();
        this.data = null;
    }

    /* ------------------------------------------------------------------ */
    /* 便捷断言                                                            */
    /* ------------------------------------------------------------------ */

    /** 条件为真则抛出异常 */
    public static void throwIf(boolean condition, ResultCode resultCode) {
        if (condition) {
            throw new BusinessException(resultCode);
        }
    }

    /** 条件为真则抛出异常（自定义提示） */
    public static void throwIf(boolean condition, ResultCode resultCode, String message) {
        if (condition) {
            throw new BusinessException(resultCode, message);
        }
    }

    /** 对象为空则抛出异常 */
    public static <T> T requireNonNull(T target, ResultCode resultCode) {
        if (target == null) {
            throw new BusinessException(resultCode);
        }
        return target;
    }
}
