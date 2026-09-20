package com.tian.textbook.common;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * SecurityContext 访问工具（无 Spring 环境时返回 null，由调用方决定 401/403）。
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static CurrentUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof CurrentUser cu) {
            return cu;
        }
        return null;
    }

    public static CurrentUser requireCurrentUser() {
        CurrentUser cu = currentUser();
        if (cu == null) {
            throw new IllegalStateException("no authenticated user in context");
        }
        return cu;
    }

    public static Long currentUserId() {
        CurrentUser cu = currentUser();
        return cu == null ? null : cu.userId();
    }

    public static boolean authenticated() {
        return currentUser() != null;
    }
}
