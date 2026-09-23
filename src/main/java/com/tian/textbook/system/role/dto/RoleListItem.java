package com.tian.textbook.system.role.dto;

import java.util.List;

/**
 * 角色列表项（BE-2 · {@code GET /api/admin/role}）。
 *
 * @param id        角色 id
 * @param roleCode  角色编码
 * @param roleName  角色名称
 * @param sort      排序
 * @param builtIn   内置角色（ADMIN/SECRETARY/TEACHER/STUDENT/SUPPLIER）：不可删除、编码不可改
 * @param userCount 绑定该角色的账号数（删除前校验用）
 * @param permCodes 该角色已授权的权限码（ADMIN 由鉴权层短路持有全部权限，此处置空数组，
 *                  前端按 builtIn 展示「全部权限（内置，不可修改）」）
 */
public record RoleListItem(
        Long id,
        String roleCode,
        String roleName,
        Integer sort,
        boolean builtIn,
        long userCount,
        List<String> permCodes) {
}
