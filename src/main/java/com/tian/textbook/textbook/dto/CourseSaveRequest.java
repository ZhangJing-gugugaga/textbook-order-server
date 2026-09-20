package com.tian.textbook.textbook.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 新增课程请求（SPEC §11.3：POST /api/admin/course）。
 *
 * <p>semesterId 缺省 = 当前 active 学期（SPEC §5.4 请求快照）；code 可空，
 * 非空时同学期内查重（uk_course 含 deleted）。</p>
 */
public record CourseSaveRequest(
        Long semesterId,
        String code,
        @NotBlank(message = "课程名称不能为空") String name) {
}
