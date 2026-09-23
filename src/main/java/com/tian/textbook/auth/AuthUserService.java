package com.tian.textbook.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tian.textbook.system.entity.SysPermission;
import com.tian.textbook.system.entity.SysRole;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.mapper.SysPermissionMapper;
import com.tian.textbook.system.mapper.SysRoleMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 登录用户加载与权限码集合（Caffeine 只读缓存，不承载业务真源；
 * 角色变更/停用时由相关服务调用 evict 失效）。
 */
@Service
public class AuthUserService {

    /** 超管角色码（BE-1：鉴权层短路，见 {@link #allPermissionCodes()}） */
    public static final String ADMIN_ROLE = "ADMIN";

    /** 全量权限码缓存的固定 key（不随用户变化） */
    private static final String ALL_PERMISSIONS_KEY = "__ALL__";

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;

    /** userId → 角色列表缓存 */
    private final Cache<Long, List<SysRole>> roleCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    /** 全量权限码缓存（BE-1：超管鉴权短路用；角色/权限变更时 evict） */
    private final Cache<String, Set<String>> allPermissionsCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(4)
            .build();

    public AuthUserService(SysUserMapper userMapper, SysRoleMapper roleMapper,
                           SysPermissionMapper permissionMapper) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.permissionMapper = permissionMapper;
    }

    public SysUser loadUser(Long userId) {
        return userMapper.selectByIdSoft(userId);
    }

    public SysUser loadByUserNo(String userNo) {
        return userMapper.selectByUserNo(userNo);
    }

    public List<SysRole> loadRoles(Long userId) {
        return roleCache.get(userId, roleMapper::selectByUserId);
    }

    /** 当前身份的权限码集合（多角色用户的界面/鉴权以 currentRole 为准，W10） */
    public Set<String> permissionsOf(List<SysRole> roles, String currentRole) {
        return roles.stream()
                .filter(r -> r.getRoleCode().equals(currentRole))
                .flatMap(r -> roleMapper.selectPermCodesByRole(r.getId()).stream())
                .collect(Collectors.toSet());
    }

    /**
     * 鉴权用权限码集合：**超管短路**，其余角色按 currentRole 查表。
     *
     * <p>甲方决策「超级管理员可以做所有事情」：超管在鉴权层持有全部权限码，
     * 所有 {@code @PreAuthorize} 对其放行（业务校验——归属/窗口/状态机——照常生效）。</p>
     *
     * <p>为什么不是「给 ADMIN 角色插满 sys_role_permission 行」：那样会让前端按权限码
     * 过滤出的侧边栏重新出现「学院秘书/任课老师/学生」等别角色分组（2026-09-22 线上缺陷），
     * 且每次新增权限码都要回填授权行。此处用短路，`data-permission.sql` 的 28 条授权保持不动。</p>
     */
    public Set<String> permissionsFor(List<SysRole> roles, String currentRole) {
        boolean admin = roles.stream().anyMatch(r -> ADMIN_ROLE.equals(r.getRoleCode()));
        return admin ? allPermissionCodes() : permissionsOf(roles, currentRole);
    }

    /**
     * 全部权限码（超管鉴权短路用；Caffeine 缓存 5 分钟，角色/权限变更时
     * {@link #evictAllPermissions()} 失效）。
     */
    public Set<String> allPermissionCodes() {
        Set<String> cached = allPermissionsCache.get(ALL_PERMISSIONS_KEY, key -> permissionMapper
                .selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysPermission>lambdaQuery()
                        .eq(SysPermission::getDeleted, 0))
                .stream().map(SysPermission::getPermCode).collect(Collectors.toSet()));
        return cached == null ? Set.of() : cached;
    }

    /** 角色/权限变更后调用（BE-2 的写操作成功后必调） */
    public void evictAllPermissions() {
        allPermissionsCache.invalidateAll();
    }

    /** 用户全部角色的权限码并集（switch-role 候选展示用） */
    public Set<String> allPermissions(List<SysRole> roles) {
        return roles.stream()
                .flatMap(r -> roleMapper.selectPermCodesByRole(r.getId()).stream())
                .collect(Collectors.toSet());
    }

    /** 角色变更/账号停用后失效缓存（token 同步失效由 role_version 保证） */
    public void evict(Long userId) {
        roleCache.invalidate(userId);
    }
}
