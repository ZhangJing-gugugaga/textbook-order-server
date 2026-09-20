package com.tian.textbook.textbook.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 编辑课程请求（SPEC §11.3：PUT /api/admin/course/{id}）。
 *
 * <p>code 传空串视为清除课程代码；学期归属不可改（course 为学期域数据）。</p>
 */
public record CourseUpdateRequest(
        String code,
        @NotBlank(message = "课程名称不能为空") String name) {
}
