package com.tian.textbook.auth.dto;

import java.util.List;

/**
 * 登录/刷新/改密/切换身份的统一签发响应（SPEC §11.1）。
 */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        long expiresIn,
        boolean mustChangePassword,
        boolean firstLoginVerified,
        List<String> roles,
        String currentRole,
        String userNo,
        String name) {
}
