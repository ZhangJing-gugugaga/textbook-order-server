package com.tian.textbook.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * 过滤器层错误渲染（ControllerAdvice 捕获不到 DispatcherServlet 之前的异常，spring-security-jwt skill）。
 */
final class SecurityErrorRenderer {

    private SecurityErrorRenderer() {
    }

    static void render(HttpServletResponse response, ErrorCode errorCode) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.setStatus(errorCode.httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(new ObjectMapper().writeValueAsString(ApiResponse.fail(errorCode)));
    }
}
