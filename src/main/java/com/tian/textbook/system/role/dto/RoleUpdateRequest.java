package com.tian.textbook.system.role.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 编辑角色请求（BE-2）：仅名称与排序可改，{@code roleCode} 创建后不可变。
 */
public record RoleUpdateRequest(
        @NotBlank(message = "角色名称不能为空") String roleName,
        Integer sort) {
}
