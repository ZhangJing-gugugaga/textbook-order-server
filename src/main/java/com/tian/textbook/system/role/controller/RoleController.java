package com.tian.textbook.system.role.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.annotation.AuditLog;
import com.tian.textbook.system.role.dto.RoleCreateRequest;
import com.tian.textbook.system.role.dto.RoleListItem;
import com.tian.textbook.system.role.dto.RolePermissionRequest;
import com.tian.textbook.system.role.dto.RoleUpdateRequest;
import com.tian.textbook.system.role.service.RoleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色管理（BE-2，SPEC §11.2 扩展）：超管可新建/编辑/删除角色、给角色配权限。
 *
 * <p>内置角色（ADMIN/SECRETARY/TEACHER/STUDENT/SUPPLIER）不可删除、编码不可改；
 * ADMIN 的权限集不可改（鉴权层短路持有全部权限，BE-1）。</p>
 */
@RestController
@RequestMapping("/api/admin/role")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService roleService;

    /** 角色列表（含绑定账号数与权限码） */
    @GetMapping
    @PreAuthorize("hasAuthority('role:manage')")
    public ApiResponse<List<RoleListItem>> list() {
        return ApiResponse.ok(roleService.list());
    }

    /** 新建角色（自定义角色默认无权限，需再调授权接口） */
    @AuditLog(action = "ROLE", resource = "sys_role")
    @PostMapping
    @PreAuthorize("hasAuthority('role:manage')")
    public ApiResponse<Long> create(@Valid @RequestBody RoleCreateRequest request) {
        return ApiResponse.ok(roleService.create(request));
    }

    /** 编辑角色（仅名称与排序） */
    @AuditLog(action = "ROLE", resource = "sys_role")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    public ApiResponse<Void> update(@PathVariable Long id, @Valid @RequestBody RoleUpdateRequest request) {
        roleService.update(id, request);
        return ApiResponse.ok();
    }

    /** 删除角色（逻辑删除 + 级联清授权；内置角色 400、仍有账号 409） */
    @AuditLog(action = "ROLE", resource = "sys_role")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('role:manage')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return ApiResponse.ok();
    }

    /** 角色-权限全量覆盖（空数组 = 收回全部权限；ADMIN → 400） */
    @AuditLog(action = "ROLE", resource = "sys_role")
    @PutMapping("/{id}/permissions")
    @PreAuthorize("hasAuthority('role:permission:assign')")
    public ApiResponse<Void> assignPermissions(@PathVariable Long id,
                                               @RequestBody RolePermissionRequest request) {
        roleService.assignPermissions(id, request);
        return ApiResponse.ok();
    }
}
