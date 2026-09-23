package com.tian.textbook.system.role.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.system.role.dto.PermissionGroup;
import com.tian.textbook.system.role.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限目录（BE-2）：按模块分组的全量权限码，供角色配置页勾选。
 */
@RestController
@RequestMapping("/api/admin/permission")
@RequiredArgsConstructor
public class PermissionController {

    private final RoleService roleService;

    /** 权限目录（按模块分组） */
    @GetMapping
    @PreAuthorize("hasAuthority('role:manage')")
    public ApiResponse<List<PermissionGroup>> catalog() {
        return ApiResponse.ok(roleService.permissionCatalog());
    }
}
