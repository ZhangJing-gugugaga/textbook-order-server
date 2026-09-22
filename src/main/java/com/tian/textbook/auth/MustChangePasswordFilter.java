package com.tian.textbook.auth;

import com.tian.textbook.common.SecurityUtils;
import com.tian.textbook.common.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 强制改密拦截过滤器（W19：must_change_password=1 或 first_login_verified=0 时，
 * 仅放行 /api/auth/**、/api/me*，业务接口一律 403）。
 */
public class MustChangePasswordFilter extends OncePerRequestFilter {

    /**
     * 首登待完成时允许的路径（显式枚举，不用 {@code /api/auth/} 前缀通配）。
     *
     * <p>此前用 {@code path.startsWith("/api/auth/")}，把 {@code /api/auth/switch-role} 一并放行——
     * 该端点会重新签发 access/refresh，等于让未完成首登校验的会话获得一份新的长效令牌。</p>
     *
     * <p>改密端点是 {@code PUT /api/me/password}，由下面的 {@link #ALLOWED_PREFIX} 覆盖。</p>
     */
    private static final java.util.Set<String> ALLOWED_PATHS = java.util.Set.of(
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/first-login/verify",
            "/api/auth/logout");

    /** 允许的路径前缀（/api/me 及其子路径，含 PUT /api/me/password） */
    private static final String ALLOWED_PREFIX = "/api/me";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var currentUser = SecurityUtils.currentUser();
        if (currentUser != null && currentUser.firstLoginPending()) {
            String path = request.getRequestURI();
            boolean allowed = ALLOWED_PATHS.contains(path) || path.startsWith(ALLOWED_PREFIX);
            if (!allowed) {
                SecurityErrorRenderer.render(response, ErrorCode.FIRST_LOGIN_REQUIRED);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/v3/api-docs") || path.startsWith("/swagger-ui") || path.equals("/error");
    }
}
