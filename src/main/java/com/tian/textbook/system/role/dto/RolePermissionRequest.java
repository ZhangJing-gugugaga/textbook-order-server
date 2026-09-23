package com.tian.textbook.system.role.dto;

import java.util.List;

/**
 * 角色-权限全量覆盖请求（BE-2 · {@code PUT /api/admin/role/{id}/permissions}）。
 *
 * <p>{@code permCodes} 允许为空数组（等于收回该角色全部权限）；含未知权限码 → 400。</p>
 */
public record RolePermissionRequest(List<String> permCodes) {
}
