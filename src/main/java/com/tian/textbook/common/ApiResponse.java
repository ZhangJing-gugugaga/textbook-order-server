package com.tian.textbook.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tian.textbook.common.error.ErrorCode;

/**
 * 统一响应包络（SPEC §11：{code, message, data}）。
 *
 * <p>成功 code = "0"；失败 code = 错误码令牌（如 TOKEN_EXPIRED / FORBIDDEN / WINDOW_CLOSED），
 * 与 PRD 模块 8 全局错误文案令牌一致。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(String code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.code, null, data);
    }

    public static <T> ApiResponse<T> ok() {
        return ok(null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.code, errorCode.defaultMessage, null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code, message, null);
    }

    /** 失败响应并携带结构化数据（如字段审查逐项错误）。 */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message, T data) {
        return new ApiResponse<>(errorCode.code, message, data);
    }
}
