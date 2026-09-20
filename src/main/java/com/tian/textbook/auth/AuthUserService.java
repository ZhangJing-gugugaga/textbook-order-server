package com.tian.textbook.auth;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.tian.textbook.system.entity.SysRole;
import com.tian.textbook.system.entity.SysUser;
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

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;

    /** userId → 角色列表缓存 */
    private final Cache<Long, List<SysRole>> roleCache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(10_000)
            .build();

    public AuthUserService(SysUserMapper userMapper, SysRoleMapper roleMapper) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
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
