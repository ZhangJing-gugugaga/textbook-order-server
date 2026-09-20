package com.tian.textbook.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 修改密码请求（8-64 位且含字母和数字，PRD 模块 1）。
 */
public record ChangePasswordRequest(
        @NotBlank(message = "原密码不能为空") String oldPassword,
        @NotBlank(message = "新密码不能为空") String newPassword) {
}
