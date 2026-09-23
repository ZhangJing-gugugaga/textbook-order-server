package com.tian.textbook.integration.system;

import com.tian.textbook.TextbookOrderServerApplication;
import com.tian.textbook.auth.JwtService;
import com.tian.textbook.common.CurrentUser;
import com.tian.textbook.common.SecurityUtils;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.support.TestDataSeeder;
import com.tian.textbook.support.TestSecurity;
import com.tian.textbook.system.entity.SysRole;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.mapper.SysRoleMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import com.tian.textbook.system.role.dto.PermissionGroup;
import com.tian.textbook.system.role.dto.RoleCreateRequest;
import com.tian.textbook.system.role.dto.RoleListItem;
import com.tian.textbook.system.role.dto.RolePermissionRequest;
import com.tian.textbook.system.role.dto.RoleUpdateRequest;
import com.tian.textbook.system.role.dto.UserRolesRequest;
import com.tian.textbook.system.role.service.RoleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 角色与权限管理集成测试（BE-2 / BE-1，甲方决策「超管能配置角色、给角色配权限、超管能做所有事情」）。
 *
 * <p>覆盖：</p>
 * <ol>
 *   <li>BE-1：超管在鉴权层持有**全部**权限码（39 条），调教师/学生自助接口不再 403；</li>
 *   <li>BE-2：新建角色 → 分配权限 → 回读一致；内置角色不可删、编码不可改；</li>
 *   <li>BE-2：删除仍有账号的角色 → 409；ADMIN 权限不可改 → 400；</li>
 *   <li>BE-2：账号角色覆盖 → 旧 token 失效（401），重新签发后权限即时生效；</li>
 *   <li>BE-2：不能移除本人最后一个超管角色（自锁保护）。</li>
 * </ol>
 */
@SpringBootTest(classes = TextbookOrderServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RolePermissionIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private TestDataSeeder seeder;
    @Autowired
    private RoleService roleService;
    @Autowired
    private SysRoleMapper roleMapper;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private JwtService jwtService;

    private Long adminId;

    @BeforeEach
    void resetDatabase() {
        seeder.cleanAll();
        seeder.seedRbac();
        // 自助接口需要 active 学期上下文（无学期时业务层返回 400「尚未激活任何学期」）
        var semester = seeder.semester("2026-2027-RBAC", null, null,
                java.time.LocalDateTime.now().minusDays(1), java.time.LocalDateTime.now().plusDays(7), 1, 1);
        seeder.semesterMapper().activateIfDraft(semester.getId(), semester.getVersion());
        // 打开窗口：提交类接口挂 @WithinWindow（窗口非 open → 409 WINDOW_CLOSED）
        seeder.semesterMapper().update(null,
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.tian.textbook.semester.entity.Semester>lambdaUpdate()
                        .eq(com.tian.textbook.semester.entity.Semester::getId, semester.getId())
                        .set(com.tian.textbook.semester.entity.Semester::getChannelOpen, 1)
                        .set(com.tian.textbook.semester.entity.Semester::getWindowStatus, "open"));
        adminId = seeder.user("ADMR", "超管", "13800000031", null, null, 1, 0, 1, "ADMIN").getId();
        asAdmin();
    }

    private void asAdmin() {
        SysUser admin = userMapper.selectByIdSoft(adminId);
        TestSecurity.authenticate(adminId, admin.getUserNo(), admin.getName(), Set.of("ADMIN"), "ADMIN",
                seeder.allPermissionCodes());
    }

    // ============ BE-1：超管全权限 ============

    @Test
    @DisplayName("BE-1：超管 /api/me/permissions 返回全部 39 条权限码（含角色专属与供货商）")
    void admin_holdsAllPermissionCodes() {
        String token = adminToken();
        ResponseEntity<Map<String, Object>> response = get("/api/me/permissions", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<String> permissions = (List<String>) response.getBody().get("data");
        assertThat(permissions).hasSize(39)
                .contains("order:form:submit", "student:order:submit", "change:request:submit",
                        "role:manage", "role:permission:assign", "supplier:order:view",
                        "semester:window:view");
    }

    @Test
    @DisplayName("BE-1：超管调教师/学生自助接口不再 403（权限门放行，业务校验仍生效）")
    void admin_selfServiceEndpoints_allowedByPermissionGate() {
        String token = adminToken();

        // 教师自助：我的课程 / 我的提交记录 → 200（超管无任课关系，返回空列表而非 403）
        assertThat(get("/api/teacher/my-courses", token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/teacher/order-forms", token).getStatusCode()).isEqualTo(HttpStatus.OK);
        // 学生自助：选购单 → 200
        assertThat(get("/api/student/order", token).getStatusCode()).isEqualTo(HttpStatus.OK);

        // 业务校验仍生效：语法合法但内容非法的明细（不存在的课程/班级/教材）→ 400 FIELD_CHECK_FAILED
        // （不是 403——证明权限门已放行；也不是 200——证明字段审查仍在跑）
        ResponseEntity<Map<String, Object>> submit = postJson("/api/teacher/order-form/submit", token,
                "{\"items\":[{\"courseId\":999999,\"classId\":999999,\"textbookId\":999999,\"quantity\":1}]}");
        assertThat(submit.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(submit.getBody().get("code")).isEqualTo("FIELD_CHECK_FAILED");
    }

    @Test
    @DisplayName("BE-2：非超管访问角色/权限接口一律 403")
    void roleEndpoints_nonAdmin_forbidden() {
        seeder.user("TCHR", "教师", "13800000032", null, null, 1, 0, 1, "TEACHER");
        String teacherToken = jwtService.issueAccessToken(
                seeder.userIdByNo("TCHR"), "TCHR", "教师", Set.of("TEACHER"), "TEACHER", 1);

        assertThat(get("/api/admin/role", teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(get("/api/admin/permission", teacherToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(putJson("/api/admin/role/1/permissions", teacherToken, "{\"permCodes\":[]}")
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ============ BE-2：角色 CRUD 与授权 ============

    @Test
    @DisplayName("BE-2：新建角色 → 分配 2 条权限 → 列表回读一致")
    void createRole_assignPermissions_readBack() {
        Long roleId = roleService.create(new RoleCreateRequest("COLLEGE_AUDIT", "学院审核员", null));

        roleService.assignPermissions(roleId, new RolePermissionRequest(
                List.of("order:form:view:college", "import:batch:view")));

        List<RoleListItem> roles = roleService.list();
        RoleListItem created = roles.stream().filter(r -> r.id().equals(roleId)).findFirst().orElseThrow();
        assertThat(created.roleCode()).isEqualTo("COLLEGE_AUDIT");
        assertThat(created.builtIn()).isFalse();
        assertThat(created.permCodes()).containsExactlyInAnyOrder(
                "order:form:view:college", "import:batch:view");
        assertThat(created.userCount()).isZero();

        // 权限目录按模块分组，且包含新增的角色管理权限
        List<PermissionGroup> catalog = roleService.permissionCatalog();
        assertThat(catalog).isNotEmpty();
        assertThat(catalog.stream().flatMap(g -> g.perms().stream()).map(PermissionGroup.PermissionItem::permCode))
                .contains("role:manage", "role:permission:assign")
                .hasSize(39);

        // 全量覆盖语义：空数组 = 收回全部权限
        roleService.assignPermissions(roleId, new RolePermissionRequest(List.of()));
        assertThat(roleService.list().stream().filter(r -> r.id().equals(roleId)).findFirst()
                .orElseThrow().permCodes()).isEmpty();
    }

    @Test
    @DisplayName("BE-2：内置角色不可删除；仍有账号绑定的角色删除 → 409")
    void deleteRole_guards() {
        SysRole adminRole = roleMapper.selectByCode("ADMIN");
        assertThatThrownBy(() -> roleService.delete(adminRole.getId()))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PARAM_INVALID))
                .hasMessageContaining("内置角色不可删除");

        Long roleId = roleService.create(new RoleCreateRequest("TEMP_ROLE", "临时角色", null));
        // 绑定一个账号 → 删除被拒（避免出现指向已删角色的悬空绑定）
        roleService.updateUserRoles(seeder.userIdByNo("ADMR"), new UserRolesRequest(
                List.of("ADMIN", "TEMP_ROLE")));
        asAdmin();
        assertThatThrownBy(() -> roleService.delete(roleId))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.STATE_CONFLICT))
                .hasMessageContaining("请先调整账号角色");

        // 解绑后可删（逻辑删除 + 级联清授权）
        roleService.updateUserRoles(seeder.userIdByNo("ADMR"), new UserRolesRequest(List.of("ADMIN")));
        asAdmin();
        roleService.delete(roleId);
        assertThat(roleService.list()).noneMatch(r -> r.id().equals(roleId));
    }

    @Test
    @DisplayName("BE-2：ADMIN 权限不可改（400）；未知权限码 → 400；角色编码重复 → 400")
    void assignPermissions_adminAndValidation_guards() {
        SysRole adminRole = roleMapper.selectByCode("ADMIN");
        assertThatThrownBy(() -> roleService.assignPermissions(adminRole.getId(),
                new RolePermissionRequest(List.of("order:form:review"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超管权限由系统内置，不可修改");

        Long roleId = roleService.create(new RoleCreateRequest("VALID_ROLE", "校验角色", null));
        assertThatThrownBy(() -> roleService.assignPermissions(roleId,
                new RolePermissionRequest(List.of("no:such:perm"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("权限码不存在");

        assertThatThrownBy(() -> roleService.create(new RoleCreateRequest("VALID_ROLE", "重复编码", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("角色编码已存在");
        assertThatThrownBy(() -> roleService.create(new RoleCreateRequest("lower_case", "非法编码", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("角色编码需为");
    }

    @Test
    @DisplayName("BE-2：账号角色覆盖 → role_version+1 + refresh 撤销（旧 token 401），新 token 权限即时生效")
    void updateUserRoles_bumpsRoleVersionAndAppliesImmediately() {
        seeder.user("MULTI", "多角色", "13800000033", null, null, 1, 0, 1, "TEACHER");
        Long userId = seeder.userIdByNo("MULTI");
        int versionBefore = userMapper.selectByIdSoft(userId).getRoleVersion();
        String oldToken = jwtService.issueAccessToken(userId, "MULTI", "多角色",
                Set.of("TEACHER"), "TEACHER", versionBefore);

        // 旧 token 可用（教师选书器：order:form:submit）
        assertThat(get("/api/teacher/textbook", oldToken).getStatusCode()).isEqualTo(HttpStatus.OK);

        roleService.updateUserRoles(userId, new UserRolesRequest(List.of("TEACHER", "SECRETARY")));

        SysUser updated = userMapper.selectByIdSoft(userId);
        assertThat(updated.getRoleVersion()).isEqualTo(versionBefore + 1);
        // 旧 token 因 role_version 失配被拒（强制重新登录）
        assertThat(get("/api/teacher/textbook", oldToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        // 重新签发后：秘书权限即时可见（本院表单查询）
        String newToken = jwtService.issueAccessToken(userId, "MULTI", "多角色",
                Set.of("TEACHER", "SECRETARY"), "SECRETARY", updated.getRoleVersion());
        assertThat(get("/api/secretary/order-forms", newToken).getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("BE-2：不能移除本人最后一个超管角色（自锁保护）；不能清空角色")
    void updateUserRoles_selfLockAndEmptyGuards() {
        assertThatThrownBy(() -> roleService.updateUserRoles(adminId, new UserRolesRequest(List.of("TEACHER"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不能移除本人最后一个超管角色");

        assertThatThrownBy(() -> roleService.updateUserRoles(adminId, new UserRolesRequest(List.of())))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请至少分配一个角色");

        assertThatThrownBy(() -> roleService.updateUserRoles(adminId, new UserRolesRequest(List.of("NO_SUCH"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("角色不存在");
    }

    @Test
    @DisplayName("BE-2：编辑角色名称与排序（编码不可改）")
    void updateRole_nameAndSort() {
        Long roleId = roleService.create(new RoleCreateRequest("EDIT_ROLE", "待改名", 99));
        roleService.update(roleId, new RoleUpdateRequest("已改名", 5));

        RoleListItem item = roleService.list().stream().filter(r -> r.id().equals(roleId))
                .findFirst().orElseThrow();
        assertThat(item.roleName()).isEqualTo("已改名");
        assertThat(item.sort()).isEqualTo(5);
        assertThat(item.roleCode()).as("编码不可改").isEqualTo("EDIT_ROLE");
    }

    // ============ 私有 ============

    private String adminToken() {
        SysUser admin = userMapper.selectByIdSoft(adminId);
        return jwtService.issueAccessToken(adminId, admin.getUserNo(), admin.getName(),
                Set.of("ADMIN"), "ADMIN", admin.getRoleVersion());
    }

    private ResponseEntity<Map<String, Object>> get(String path, String token) {
        return restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers(token)),
                new ParameterizedTypeReference<>() {
                });
    }

    private ResponseEntity<Map<String, Object>> postJson(String path, String token, String body) {
        HttpHeaders headers = headers(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {
                });
    }

    private ResponseEntity<Map<String, Object>> putJson(String path, String token, String body) {
        HttpHeaders headers = headers(token);
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers),
                new ParameterizedTypeReference<>() {
                });
    }

    private HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /** 供 SecurityUtils 依赖的方法不在此用例内使用；保留以显式表达「无隐式登录态」 */
    @SuppressWarnings("unused")
    private CurrentUser currentUserOrNull() {
        return SecurityUtils.currentUser();
    }
}
