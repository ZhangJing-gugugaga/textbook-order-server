package com.tian.textbook.auth;

import com.tian.textbook.auth.dto.*;
import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.util.IpUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证与会话（SPEC §11.1 契约基线）。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.ok(authService.login(request, IpUtils.clientIp(httpRequest),
                httpRequest.getHeader("X-Device-Id")));
    }

    @PostMapping("/refresh")
    public ApiResponse<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request,
                                             @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        return ApiResponse.ok(authService.refresh(request, deviceId));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.ok();
    }

    /** 首登校验（W19：手机号后 4 位 / openid 绑定），待改密状态可访问 */
    @PostMapping("/first-login/verify")
    public ApiResponse<Void> firstLoginVerify(@Valid @RequestBody FirstLoginVerifyRequest request) {
        authService.firstLoginVerify(request);
        return ApiResponse.ok();
    }

    /** 切换身份（W10：数据范围不变） */
    @PostMapping("/switch-role")
    public ApiResponse<AuthResponse> switchRole(@Valid @RequestBody SwitchRoleRequest request,
                                                @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        return ApiResponse.ok(authService.switchRole(request, deviceId));
    }
}
