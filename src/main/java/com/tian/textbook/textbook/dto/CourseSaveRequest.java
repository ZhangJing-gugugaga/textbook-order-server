package com.tian.textbook.textbook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新增课程请求（SPEC §11.3：POST /api/admin/course）。
 *
 * <p>semesterId 缺省 = 当前 active 学期（SPEC §5.4 请求快照）；code 可空，
 * 非空时同学期内查重（uk_course 含 deleted）。@Size 与 DDL 列宽一致
 * （code VARCHAR(32) / name VARCHAR(128)）。</p>
 */
public record CourseSaveRequest(
        Long semesterId,
        @Size(max = 32, message = "课程代码不能超过 32 字符") String code,
        @NotBlank(message = "课程名称不能为空") @Size(max = 128, message = "课程名称不能超过 128 字符") String name) {
}
