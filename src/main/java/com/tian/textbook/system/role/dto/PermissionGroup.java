package com.tian.textbook.system.role.dto;

import java.util.List;

/**
 * 权限目录项（BE-2 · {@code GET /api/admin/permission}）：按模块分组，供角色配置页勾选。
 *
 * @param module 模块名（sys_permission.module）
 * @param perms  该模块下的权限码
 */
public record PermissionGroup(
        String module,
        List<PermissionItem> perms) {

    /** @param permCode 权限码（module:business:action） @param permName 中文名 */
    public record PermissionItem(String permCode, String permName) {
    }
}
