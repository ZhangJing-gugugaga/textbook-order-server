package com.tian.textbook.system.role.service;

import com.tian.textbook.auth.AuthUserService;
import com.tian.textbook.common.CurrentUser;
import com.tian.textbook.common.SecurityUtils;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.system.entity.SysPermission;
import com.tian.textbook.system.entity.SysRole;
import com.tian.textbook.system.entity.SysRolePermission;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.entity.SysUserRole;
import com.tian.textbook.system.mapper.SysPermissionMapper;
import com.tian.textbook.system.mapper.SysRoleMapper;
import com.tian.textbook.system.mapper.SysRolePermissionMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import com.tian.textbook.system.mapper.SysUserRoleMapper;
import com.tian.textbook.system.role.dto.PermissionGroup;
import com.tian.textbook.system.role.dto.RoleCreateRequest;
import com.tian.textbook.system.role.dto.RoleListItem;
import com.tian.textbook.system.role.dto.RolePermissionRequest;
import com.tian.textbook.system.role.dto.RoleUpdateRequest;
import com.tian.textbook.system.role.dto.UserRolesRequest;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 角色与权限管理（BE-2，甲方决策「超管能配置角色、给角色配权限」）。
 *
 * <p>要点：</p>
 * <ul>
 *   <li>内置角色（ADMIN/SECRETARY/TEACHER/STUDENT/SUPPLIER）：编码不可改、不可删除；</li>
 *   <li>ADMIN 的权限集不可改——它在鉴权层短路持有全部权限（BE-1），改授权行没有意义，
 *       且会造成「库里 28 条 vs 实际 39 条」的认知混乱；</li>
 *   <li>任何权限变更成功后必须 {@code evictAllPermissions()}（全量权限码缓存 5 分钟）；</li>
 *   <li>账号角色变更成功后 {@code role_version+1} + 撤销全部 refresh + evict，权限即时生效
 *       （代价是该账号被强制下线，前端须提示）；</li>
 *   <li>删除角色前校验占用：仍有账号绑定时 409，避免出现「账号指向已删角色」的悬空绑定。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    /** 内置角色（不可删除、编码不可改） */
    public static final Set<String> BUILT_IN_ROLES =
            Set.of("ADMIN", "SECRETARY", "TEACHER", "STUDENT", "SUPPLIER");

    /** 超管角色码：权限集由鉴权层短路提供，不可通过接口修改 */
    public static final String ADMIN_ROLE = "ADMIN";

    /** 角色编码：2–32 位，大写字母开头，其余为大写字母/数字/下划线 */
    private static final Pattern ROLE_CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");

    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysUserMapper userMapper;
    private final AuthUserService authUserService;
    private final AuditService auditService;

    // ============ 角色 CRUD ============

    /** 角色列表（含绑定账号数与权限码）。 */
    @Transactional(readOnly = true)
    public List<RoleListItem> list() {
        List<SysRole> roles = roleMapper.selectAllActive();
        List<RoleListItem> items = new ArrayList<>(roles.size());
        for (SysRole role : roles) {
            boolean admin = ADMIN_ROLE.equals(role.getRoleCode());
            // ADMIN 返回**有效**权限集（鉴权短路的全部权限码）：库里只有 28 条授权行，
            // 但超管实际持有 39 条，列表若回显 28 会让管理员以为有 11 条权限「没勾上」。
            List<String> permCodes = admin
                    ? authUserService.allPermissionCodes().stream().sorted().toList()
                    : roleMapper.selectPermCodesByRole(role.getId()).stream().sorted().toList();
            items.add(new RoleListItem(role.getId(), role.getRoleCode(), role.getRoleName(),
                    role.getSort(), BUILT_IN_ROLES.contains(role.getRoleCode()),
                    userRoleMapper.countUsersByRole(role.getId()), permCodes));
        }
        return items;
    }

    /** 权限目录（按模块分组，供角色配置页勾选）。 */
    @Transactional(readOnly = true)
    public List<PermissionGroup> permissionCatalog() {
        List<SysPermission> permissions = permissionMapper.selectList(
                Wrappers.<SysPermission>lambdaQuery().eq(SysPermission::getDeleted, 0));
        Map<String, List<PermissionGroup.PermissionItem>> byModule = new LinkedHashMap<>();
        permissions.stream()
                .sorted(Comparator.comparing(SysPermission::getModule)
                        .thenComparing(SysPermission::getPermCode))
                .forEach(p -> byModule.computeIfAbsent(p.getModule(), k -> new ArrayList<>())
                        .add(new PermissionGroup.PermissionItem(p.getPermCode(), p.getPermName())));
        List<PermissionGroup> groups = new ArrayList<>(byModule.size());
        byModule.forEach((module, perms) -> groups.add(new PermissionGroup(module, perms)));
        return groups;
    }

    /** 新建角色（自定义角色默认无任何权限，需再调授权接口）。 */
    @Transactional
    public Long create(RoleCreateRequest request) {
        String roleCode = request.roleCode() == null ? "" : request.roleCode().trim();
        if (!ROLE_CODE_PATTERN.matcher(roleCode).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "角色编码需为 2-32 位大写字母/数字/下划线，且以字母开头");
        }
        if (roleMapper.selectByCode(roleCode) != null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "角色编码已存在");
        }
        SysRole role = new SysRole();
        role.setRoleCode(roleCode);
        role.setRoleName(request.roleName().trim());
        role.setSort(request.sort() != null ? request.sort() : nextSort());
        role.setDeleted(0L);
        roleMapper.insert(role);
        auditService.record(AuditService.ROLE, "sys_role", String.valueOf(role.getId()),
                Map.of("op", "create", "roleCode", roleCode, "roleName", role.getRoleName()));
        log.info("新建角色: id={}, code={}", role.getId(), roleCode);
        return role.getId();
    }

    /** 编辑角色（仅名称与排序；编码不可改）。 */
    @Transactional
    public void update(Long id, RoleUpdateRequest request) {
        SysRole role = requireRole(id);
        String before = role.getRoleName();
        roleMapper.update(null, Wrappers.<SysRole>lambdaUpdate()
                .eq(SysRole::getId, id)
                .eq(SysRole::getDeleted, 0)
                .set(SysRole::getRoleName, request.roleName().trim())
                .set(SysRole::getSort, request.sort() != null ? request.sort() : role.getSort()));
        auditService.record(AuditService.ROLE, "sys_role", String.valueOf(id),
                Map.of("op", "update", "roleCode", role.getRoleCode(),
                        "before", before, "after", request.roleName().trim()));
    }

    /** 删除角色（逻辑删除 + 级联清授权）。内置角色不可删；仍有账号绑定 → 409。 */
    @Transactional
    public void delete(Long id) {
        SysRole role = requireRole(id);
        if (BUILT_IN_ROLES.contains(role.getRoleCode())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "内置角色不可删除");
        }
        long users = userRoleMapper.countUsersByRole(id);
        if (users > 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT,
                    "该角色仍有 " + users + " 个账号，请先调整账号角色");
        }
        long now = System.currentTimeMillis();
        int rows = roleMapper.update(null, Wrappers.<SysRole>lambdaUpdate()
                .eq(SysRole::getId, id)
                .eq(SysRole::getDeleted, 0)
                .set(SysRole::getDeleted, now));
        if (rows == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        rolePermissionMapper.softDeleteByRole(id, now);
        authUserService.evictAllPermissions();
        auditService.record(AuditService.ROLE, "sys_role", String.valueOf(id),
                Map.of("op", "delete", "roleCode", role.getRoleCode(), "roleName", role.getRoleName()));
        log.info("删除角色: id={}, code={}", id, role.getRoleCode());
    }

    // ============ 角色-权限分配 ============

    /** 角色-权限全量覆盖（空数组 = 收回全部权限）。ADMIN 不可改。 */
    @Transactional
    public void assignPermissions(Long id, RolePermissionRequest request) {
        SysRole role = requireRole(id);
        if (ADMIN_ROLE.equals(role.getRoleCode())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "超管权限由系统内置，不可修改");
        }
        List<String> wanted = normalizeCodes(request == null ? null : request.permCodes());
        Map<String, Long> known = new LinkedHashMap<>();
        for (SysPermission permission : permissionMapper.selectList(
                Wrappers.<SysPermission>lambdaQuery().eq(SysPermission::getDeleted, 0))) {
            known.put(permission.getPermCode(), permission.getId());
        }
        for (String code : wanted) {
            if (!known.containsKey(code)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "权限码不存在: " + code);
            }
        }
        List<String> before = roleMapper.selectPermCodesByRole(id);
        long now = System.currentTimeMillis();
        rolePermissionMapper.softDeleteByRole(id, now);
        for (String code : wanted) {
            SysRolePermission row = new SysRolePermission();
            row.setRoleId(id);
            row.setPermId(known.get(code));
            row.setDeleted(0L);
            rolePermissionMapper.insert(row);
        }
        // 权限集变了 → 全量权限码缓存失效（BE-1 的超管短路读的就是它）
        authUserService.evictAllPermissions();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("op", "assign-perms");
        detail.put("roleCode", role.getRoleCode());
        detail.put("before", before);
        detail.put("after", wanted);
        auditService.record(AuditService.ROLE, "sys_role", String.valueOf(id), detail);
        log.info("角色权限覆盖: roleId={}, code={}, {} → {} 条",
                id, role.getRoleCode(), before.size(), wanted.size());
    }

    // ============ 账号角色 ============

    /**
     * 账号角色全量覆盖（多角色并集，W10）。
     *
     * <p>成功后 {@code role_version+1} + 撤销全部 refresh：旧 token 立即失效，权限即时生效，
     * 该账号需重新登录（响应体不下发新 token，前端须提示）。</p>
     */
    @Transactional
    public void updateUserRoles(Long targetUserId, UserRolesRequest request) {
        SysUser target = userMapper.selectByIdSoft(targetUserId);
        if (target == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "账号不存在");
        }
        List<String> codes = normalizeCodes(request == null ? null : request.roleCodes());
        if (codes.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请至少分配一个角色");
        }
        List<SysRole> roles = new ArrayList<>(codes.size());
        for (String code : codes) {
            SysRole role = roleMapper.selectByCode(code);
            if (role == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "角色不存在: " + code);
            }
            roles.add(role);
        }
        CurrentUser operator = SecurityUtils.requireCurrentUser();
        if (targetUserId.equals(operator.userId())
                && codes.stream().noneMatch(ADMIN_ROLE::equals)) {
            // 自锁保护：改掉自己的最后一个超管角色会让系统失去超管（且本人当场被踢下线）
            throw new BizException(ErrorCode.PARAM_INVALID, "不能移除本人最后一个超管角色");
        }
        List<String> before = roleMapper.selectByUserId(targetUserId).stream()
                .map(SysRole::getRoleCode).toList();
        long now = System.currentTimeMillis();
        userRoleMapper.softDeleteByUser(targetUserId, now);
        for (SysRole role : roles) {
            SysUserRole row = new SysUserRole();
            row.setUserId(targetUserId);
            row.setRoleId(role.getId());
            row.setDeleted(0L);
            userRoleMapper.insert(row);
        }
        // 角色变了 → 旧 token 失效（强制重新登录）+ 角色缓存失效
        userMapper.incrRoleVersion(targetUserId);
        userMapper.revokeAllTokens(targetUserId);
        authUserService.evict(targetUserId);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("op", "update-roles");
        detail.put("userNo", target.getUserNo());
        detail.put("before", before);
        detail.put("after", codes);
        auditService.record(AuditService.ACCOUNT, "sys_user", String.valueOf(targetUserId), detail);
        log.info("账号角色覆盖: userId={}, userNo={}, {} → {}", targetUserId, target.getUserNo(),
                before, codes);
    }

    // ============ 私有 ============

    private SysRole requireRole(Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null || role.getDeleted() == null || role.getDeleted() != 0L) {
            throw new BizException(ErrorCode.NOT_FOUND, "角色不存在");
        }
        return role;
    }

    /** 去空白 + 去重（保持入参顺序，便于审计里 before/after 可读）。 */
    private List<String> normalizeCodes(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String code : codes) {
            if (code != null && !code.isBlank()) {
                unique.add(code.trim());
            }
        }
        return new ArrayList<>(unique);
    }

    private int nextSort() {
        return roleMapper.selectAllActive().stream()
                .map(SysRole::getSort)
                .filter(java.util.Objects::nonNull)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }
}
