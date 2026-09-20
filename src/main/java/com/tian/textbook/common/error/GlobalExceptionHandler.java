package com.tian.textbook.common.error;

import com.tian.textbook.common.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理（rest-api-conventions：统一 @RestControllerAdvice，控制器内不 try/catch）。
 *
 * <p>过滤器层（JWT 鉴权/403）的异常在 SecurityConfig 的 entryPoint/accessDeniedHandler 渲染，
 * ControllerAdvice 捕获不到 DispatcherServlet 之前的异常。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Object>> handleBiz(BizException ex) {
        ErrorCode code = ex.getErrorCode();
        if (code.httpStatus >= 500) {
            log.warn("业务异常: code={}, message={}", code.code, ex.getMessage());
        }
        return ResponseEntity.status(code.httpStatus)
                .body(new ApiResponse<>(code.code, ex.getMessage(), ex.getData()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public ResponseEntity<ApiResponse<List<String>>> handleValidation(Exception ex) {
        List<String> details;
        if (ex instanceof MethodArgumentNotValidException manve) {
            details = manve.getBindingResult().getFieldErrors().stream()
                    .map(e -> e.getField() + ": " + e.getDefaultMessage())
                    .collect(Collectors.toList());
        } else {
            details = ((BindException) ex).getBindingResult().getFieldErrors().stream()
                    .map(e -> e.getField() + ": " + e.getDefaultMessage())
                    .collect(Collectors.toList());
        }
        return ResponseEntity.badRequest()
                .body(new ApiResponse<>(ErrorCode.PARAM_INVALID.code, "请求参数有误", details));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_INVALID));
    }

    @ExceptionHandler({MissingServletRequestPartException.class})
    public ResponseEntity<ApiResponse<Void>> handleMissingPart(MissingServletRequestPartException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_INVALID));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_INVALID));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleUploadTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(ErrorCode.FILE_TOO_LARGE.httpStatus)
                .body(ApiResponse.fail(ErrorCode.FILE_TOO_LARGE));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(ErrorCode.FORBIDDEN.httpStatus)
                .body(ApiResponse.fail(ErrorCode.FORBIDDEN));
    }

    /** 唯一约束等数据完整性冲突 → 409 提示刷新（如重提并发、切换 version 冲突）。 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("数据完整性冲突: {}", ex.getMessage());
        return ResponseEntity.status(ErrorCode.STATE_CONFLICT.httpStatus)
                .body(ApiResponse.fail(ErrorCode.STATE_CONFLICT));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception ex) {
        log.error("未处理异常", ex);
        return ResponseEntity.internalServerError().body(ApiResponse.fail(ErrorCode.SERVER_ERROR));
    }
}
