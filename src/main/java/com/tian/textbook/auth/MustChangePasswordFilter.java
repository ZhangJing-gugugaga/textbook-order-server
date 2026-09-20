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

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        var currentUser = SecurityUtils.currentUser();
        if (currentUser != null && currentUser.firstLoginPending()) {
            String path = request.getRequestURI();
            boolean allowed = path.startsWith("/api/auth/") || path.startsWith("/api/me");
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
