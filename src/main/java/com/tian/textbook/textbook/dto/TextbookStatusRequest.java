package com.tian.textbook.textbook.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 教材停用/启用请求（SPEC §11.3：POST /api/admin/textbook/{id}/status）。
 */
public record TextbookStatusRequest(
        @NotNull(message = "状态不能为空") Integer status) {
}
