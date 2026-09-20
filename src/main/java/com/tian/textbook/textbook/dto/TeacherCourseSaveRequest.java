package com.tian.textbook.textbook.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 新增任课关系请求（SPEC §11.3：POST /api/admin/teacher-course）。
 *
 * <p>semesterId 缺省 = 当前 active 学期；uk_tc(semester_id, teacher_id, course_id,
 * class_id, deleted) 唯一，重复提交抛 409。</p>
 */
public record TeacherCourseSaveRequest(
        Long semesterId,
        @NotNull(message = "教师不能为空") Long teacherId,
        @NotNull(message = "课程不能为空") Long courseId,
        @NotNull(message = "班级不能为空") Long classId) {
}
