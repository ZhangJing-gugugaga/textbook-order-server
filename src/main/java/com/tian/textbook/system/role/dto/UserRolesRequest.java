package com.tian.textbook.system.role.dto;

import java.util.List;

/**
 * 账号角色全量覆盖请求（BE-2 · {@code PUT /api/admin/user/{id}/roles}）。
 *
 * <p>至少 1 个角色；成功后该账号 {@code role_version+1} 且全部 refresh 撤销（强制重新登录，
 * 权限即时生效）。</p>
 */
public record UserRolesRequest(List<String> roleCodes) {
}
