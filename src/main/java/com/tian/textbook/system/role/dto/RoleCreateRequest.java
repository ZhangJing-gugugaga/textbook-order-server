package com.tian.textbook.system.role.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 新建角色请求（BE-2）。
 *
 * @param roleCode 角色编码（{@code ^[A-Z][A-Z0-9_]{1,31}$}，创建后不可改）
 * @param roleName 角色名称
 * @param sort     排序（缺省 = 排在最后，由 Service 计算）
 */
public record RoleCreateRequest(
        @NotBlank(message = "角色编码不能为空") String roleCode,
        @NotBlank(message = "角色名称不能为空") String roleName,
        Integer sort) {
}
