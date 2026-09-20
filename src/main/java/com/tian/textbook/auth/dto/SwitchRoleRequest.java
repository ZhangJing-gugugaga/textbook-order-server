package com.tian.textbook.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 切换身份请求（W10：仅切换 currentRole 与权限码呈现，不放宽数据范围）。
 */
public record SwitchRoleRequest(
        @NotBlank(message = "roleCode 不能为空") String roleCode) {
}
