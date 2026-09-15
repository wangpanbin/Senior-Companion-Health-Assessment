package org.company.nianglin.common;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应结构。
 *
 * <p>所有接口（含错误响应）一律返回本结构，前端只需按 {@code code} 分支处理。</p>
 *
 * <pre>
 * {
 *   "code": 200,
 *   "message": "操作成功",
 *   "data": { ... }
 * }
 * </pre>
 *
 * @param <T> 业务数据类型
 * @author 银龄伴诊团队
 */
@Data
@Accessors(chain = true)
@Schema(description = "统一响应结构")
public class Result<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "业务响应码，200 表示成功，其余见 docs/api/README.md 错误码表", example = "200")
    private Integer code;

    @Schema(description = "提示信息，可直接展示给用户", example = "操作成功")
    private String message;

    @Schema(description = "业务数据，失败时为 null")
    private T data;

    /* ------------------------------------------------------------------ */
    /* 构造                                                                */
    /* ------------------------------------------------------------------ */

    public Result() {
    }

    public Result(Integer code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    /* ------------------------------------------------------------------ */
    /* 成功                                                                */
    /* ------------------------------------------------------------------ */

    public static <T> Result<T> success() {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), null);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMessage(), data);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>(ResultCode.SUCCESS.getCode(), message, data);
    }

    /* ------------------------------------------------------------------ */
    /* 失败                                                                */
    /* ------------------------------------------------------------------ */

    public static <T> Result<T> fail(ResultCode resultCode) {
        return new Result<>(resultCode.getCode(), resultCode.getMessage(), null);
    }

    public static <T> Result<T> fail(ResultCode resultCode, String message) {
        return new Result<>(resultCode.getCode(), message, null);
    }

    public static <T> Result<T> fail(Integer code, String message) {
        return new Result<>(code, message, null);
    }

    public static <T> Result<T> fail(String message) {
        return new Result<>(ResultCode.SYSTEM_ERROR.getCode(), message, null);
    }

    /* ------------------------------------------------------------------ */
    /* 辅助                                                                */
    /* ------------------------------------------------------------------ */

    @Schema(hidden = true)
    public boolean isSuccess() {
        return ResultCode.SUCCESS.getCode().equals(this.code);
    }
}
