package com.tian.textbook.common.error;

import lombok.Getter;

/**
 * 业务异常：携带错误码令牌与 HTTP 状态，由 GlobalExceptionHandler 统一渲染为包络。
 *
 * <p>可附带结构化 data（如字段审查逐项错误 field/rule/message，契约冻结回显格式）。</p>
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Object data;

    public BizException(ErrorCode errorCode) {
        super(errorCode.defaultMessage);
        this.errorCode = errorCode;
        this.data = null;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
        this.data = null;
    }

    public BizException(ErrorCode errorCode, String message, Object data) {
        super(message);
        this.errorCode = errorCode;
        this.data = data;
    }

    /** 字段审查失败（400，data = 逐项错误列表）。 */
    public static BizException fieldCheckFailed(int issueCount, Object issues) {
        return new BizException(ErrorCode.FIELD_CHECK_FAILED,
                ErrorCode.FIELD_CHECK_FAILED.format(issueCount), issues);
    }
}
