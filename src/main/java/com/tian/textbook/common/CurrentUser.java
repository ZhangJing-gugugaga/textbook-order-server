package com.tian.textbook.common;

import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.Set;

/**
 * 当前登录用户主体（JWT 载荷 + 库中权限码集合，由 JwtAuthFilter 写入 SecurityContext）。
 *
 * @param userId              用户 id
 * @param userNo              学号/工号
 * @param name                姓名
 * @param roles               全部角色码（多角色并集，W10）
 * @param currentRole         当前身份（switch-role 切换，仅影响权限码呈现，不放宽数据范围）
 * @param roleVersion         角色版本号，与库中比对不一致即失效旧 token
 * @param permissions         当前身份的权限码集合（@PreAuthorize hasAuthority 依据）
 * @param mustChangePassword  初始密码待改密（W19）
 * @param firstLoginVerified  首登校验是否通过（W19）
 */
public record CurrentUser(Long userId, String userNo, String name,
                          Set<String> roles, String currentRole,
                          int roleVersion, Collection<String> permissions,
                          boolean mustChangePassword, boolean firstLoginVerified) {

    public boolean hasRole(String roleCode) {
        return roles != null && roles.contains(roleCode);
    }

    public boolean isAdmin() {
        return hasRole("ADMIN");
    }

    public boolean hasPermission(String permCode) {
        return permissions != null && permissions.contains(permCode);
    }

    /** 首登未完成：业务接口一律 403（仅 /api/auth/**、/api/me* 放行，W19） */
    public boolean firstLoginPending() {
        return mustChangePassword || !firstLoginVerified;
    }

    public static CurrentUser current() {
        return SecurityUtils.currentUser();
    }
}
