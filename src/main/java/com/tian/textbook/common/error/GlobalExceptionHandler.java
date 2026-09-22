package com.tian.textbook.common.error;

import com.fasterxml.jackson.databind.JsonMappingException.Reference;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.util.TimeFormats;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    /**
     * 请求体不可解析（JSON 语法错 / 字段类型不匹配）→ 400。
     *
     * <p>时间字段单独给出可操作提示：默认文案只有「请求参数有误」，联调时看不出是哪个字段、
     * 该用什么格式（历史缺陷：body 传 {@code 2026-09-21 09:30:00} 时只回这一句）。
     * 其余情况维持原样（不透传 Jackson 原始消息，避免泄露类名与内部结构）。</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<List<String>>> handleNotReadable(HttpMessageNotReadableException ex) {
        InvalidFormatException formatError = findInvalidFormat(ex);
        if (formatError != null && isTemporal(formatError.getTargetType())) {
            String field = fieldPath(formatError);
            String detail = (field.isEmpty() ? "时间" : field) + ": " + TimeFormats.INPUT_HINT;
            return ResponseEntity.badRequest()
                    .body(new ApiResponse<>(ErrorCode.PARAM_INVALID.code, "请求参数有误", List.of(detail)));
        }
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_INVALID));
    }

    /** 取最内层的 {@link InvalidFormatException}（Jackson 会把它包进 JsonMappingException 链）。 */
    private static InvalidFormatException findInvalidFormat(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof InvalidFormatException ife) {
                return ife;
            }
        }
        return null;
    }

    /** 目标类型是否为日期时间（含 JSR-310 与 java.util.Date）。 */
    private static boolean isTemporal(Class<?> targetType) {
        return targetType != null
                && (java.time.temporal.Temporal.class.isAssignableFrom(targetType)
                || java.util.Date.class.isAssignableFrom(targetType));
    }

    /** 字段路径（如 {@code windowStart}；嵌套时取最内层字段名）。 */
    private static String fieldPath(InvalidFormatException ex) {
        return ex.getPath().stream()
                .map(Reference::getFieldName)
                .filter(java.util.Objects::nonNull)
                .reduce((first, second) -> second)
                .orElse("");
    }

    /**
     * 路径/查询参数类型不匹配（如 {@code ?page=abc}）→ 400。
     *
     * <p>本 advice 的处理器优先级高于 Spring 默认的 DefaultHandlerExceptionResolver，
     * 缺少这几类处理器时它们会被 {@link #handleGeneric} 兜底成 500 SERVER_ERROR，
     * 前端无法区分「客户端参数错」与「服务端故障」。</p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<List<String>>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String detail = ex.getName() + ": 参数类型不正确";
        return ResponseEntity.badRequest()
                .body(new ApiResponse<>(ErrorCode.PARAM_INVALID.code, "请求参数有误", List.of(detail)));
    }

    /**
     * 请求方法不被支持 → 405（而非 500）。
     *
     * <p>按 HTTP 规范回填 {@code Allow} 头，告知客户端该资源支持的方法
     * （Spring 默认的 DefaultHandlerExceptionResolver 会带，但本 advice 优先级更高，
     * 不显式设置就会丢掉）。</p>
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.httpStatus);
        if (ex.getSupportedHttpMethods() != null && !ex.getSupportedHttpMethods().isEmpty()) {
            builder.allow(ex.getSupportedHttpMethods().toArray(new org.springframework.http.HttpMethod[0]));
        }
        return builder.body(ApiResponse.fail(ErrorCode.METHOD_NOT_ALLOWED));
    }

    /** Content-Type 不被支持 → 415（而非 500）。 */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException ex) {
        return ResponseEntity.status(ErrorCode.MEDIA_TYPE_NOT_SUPPORTED.httpStatus)
                .body(ApiResponse.fail(ErrorCode.MEDIA_TYPE_NOT_SUPPORTED));
    }

    /** 缺少必需请求头 → 400。 */
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_INVALID));
    }

    /** 不支持的 HTTP 方法/媒体类型之外的 Servlet 请求错误 → 400。 */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ApiResponse<Void>> handleServletRequestBinding(ServletRequestBindingException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.fail(ErrorCode.PARAM_INVALID));
    }

    /** 静态资源/未知路径的 404（NoResourceFoundException 等）。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.httpStatus).body(ApiResponse.fail(ErrorCode.NOT_FOUND));
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
