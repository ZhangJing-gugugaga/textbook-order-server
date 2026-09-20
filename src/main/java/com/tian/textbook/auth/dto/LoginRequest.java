package com.tian.textbook.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求。
 */
public record LoginRequest(
        @NotBlank(message = "账号不能为空") String userNo,
        @NotBlank(message = "密码不能为空") String password) {
}
