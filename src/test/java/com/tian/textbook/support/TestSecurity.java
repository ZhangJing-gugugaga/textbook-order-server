package com.tian.textbook.support;

import com.tian.textbook.common.CurrentUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 测试用 SecurityContext 装配（服务直接调用路径下替代 JwtAuthFilter）。
 *
 * <p>与 {@code JwtAuthFilter} 同一主体构造方式：principal = CurrentUser，
 * authorities = 当前身份权限码集合（@PreAuthorize hasAuthority 依据）。</p>
 */
public final class TestSecurity {

    private TestSecurity() {
    }

    public static void authenticate(CurrentUser user) {
        List<SimpleGrantedAuthority> authorities = user.permissions() == null ? List.of()
                : user.permissions().stream().map(SimpleGrantedAuthority::new).toList();
        var authentication = new UsernamePasswordAuthenticationToken(user, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    public static void authenticate(Long userId, String userNo, String name, Set<String> roles,
                                    String currentRole, Collection<String> permissions) {
        authenticate(new CurrentUser(userId, userNo, name, roles, currentRole, 1, permissions,
                false, true));
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
