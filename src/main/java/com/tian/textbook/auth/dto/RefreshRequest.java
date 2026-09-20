package com.tian.textbook.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * refresh 请求。
 */
public record RefreshRequest(
        @NotBlank(message = "refreshToken 不能为空") String refreshToken) {
}
