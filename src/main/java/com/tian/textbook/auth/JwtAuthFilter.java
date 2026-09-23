package com.tian.textbook.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.CurrentUser;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.system.entity.SysRole;
import com.tian.textbook.system.entity.SysUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JWT 鉴权过滤器（SPEC §4 过滤器链第一环）。
 *
 * <p>解析 access → 校验签名/过期/类型 → 比对 role_version 与用户状态 → 写 SecurityContext。
 * 401 三类语义（契约冻结项）：TOKEN_EXPIRED（可 refresh 重放）/ REFRESH_INVALID（角色版本失效，
 * 强制登出）/ ACCOUNT_DISABLED（账号停用，强制登出）。</p>
 */
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private final AuthUserService authUserService;
    private final JwtService jwtService;

    public JwtAuthFilter(AuthUserService authUserService, JwtService jwtService) {
        this.authUserService = authUserService;
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            // 无 token：放行，由 entry point 对受保护路由返回 401（公开路由正常访问）
            filterChain.doFilter(request, response);
            return;
        }
        String token = header.substring(7);
        try {
            Claims claims = jwtService.parseAccessToken(token);
            Long userId = Long.valueOf(claims.getSubject());
            SysUser user = authUserService.loadUser(userId);
            if (user == null) {
                SecurityErrorRenderer.render(response, ErrorCode.REFRESH_INVALID);
                return;
            }
            if (!Integer.valueOf(1).equals(user.getStatus())) {
                // 账号停用：token 与 refresh 即时失效（模块 1 状态机）
                SecurityErrorRenderer.render(response, ErrorCode.ACCOUNT_DISABLED);
                return;
            }
            int tokenRoleVersion = claims.get("rv", Integer.class);
            if (!Integer.valueOf(tokenRoleVersion).equals(user.getRoleVersion())) {
                // 角色版本失效 → 强制登出
                SecurityErrorRenderer.render(response, ErrorCode.REFRESH_INVALID);
                return;
            }
            List<SysRole> roles = authUserService.loadRoles(userId);
            Set<String> roleCodes = roles.stream().map(SysRole::getRoleCode).collect(Collectors.toSet());
            String currentRole = claims.get("cur", String.class);
            if (currentRole == null || !roleCodes.contains(currentRole)) {
                currentRole = roleCodes.iterator().next();
            }
            CurrentUser principal = new CurrentUser(userId, user.getUserNo(), user.getName(),
                    roleCodes, currentRole, user.getRoleVersion(),
                    // 超管短路：ADMIN 在鉴权层持有全部权限码（BE-1，甲方决策「超管可以做所有事情」）
                    authUserService.permissionsFor(roles, currentRole),
                    Integer.valueOf(1).equals(user.getMustChangePassword()),
                    Integer.valueOf(1).equals(user.getFirstLoginVerified()));
            var authentication = new UsernamePasswordAuthenticationToken(principal, null,
                    principal.permissions().stream().map(SimpleGrantedAuthority::new).toList());
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (ExpiredJwtException e) {
            SecurityErrorRenderer.render(response, ErrorCode.TOKEN_EXPIRED);
            return;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            SecurityErrorRenderer.render(response, ErrorCode.TOKEN_INVALID);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
