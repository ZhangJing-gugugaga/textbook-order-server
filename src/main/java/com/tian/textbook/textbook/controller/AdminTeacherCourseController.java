package com.tian.textbook.textbook.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.textbook.dto.TeacherCourseListItem;
import com.tian.textbook.textbook.dto.TeacherCourseSaveRequest;
import com.tian.textbook.textbook.entity.TeacherCourse;
import com.tian.textbook.textbook.service.TeacherCourseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 任课关系维护（SPEC §11.3 契约基线，/api/admin/teacher-course/**）。
 *
 * <p>导入/模板下载由导入导出模块实现，不在此控制器。</p>
 */
@RestController
@RequestMapping("/api/admin/teacher-course")
@RequiredArgsConstructor
public class AdminTeacherCourseController {

    private final TeacherCourseService teacherCourseService;

    /** 任课关系列表（含课程名/教师名/班级名；semesterId 缺省 = active 学期） */
    @GetMapping
    @PreAuthorize("hasAuthority('course:teacher:manage')")
    public ApiResponse<List<TeacherCourseListItem>> list(
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false) Long teacherId,
            @RequestParam(required = false) Long classId) {
        return ApiResponse.ok(teacherCourseService.list(semesterId, teacherId, classId));
    }

    /** 新增任课关系（教师须带 TEACHER 角色；uk_tc 查重） */
    @PostMapping
    @PreAuthorize("hasAuthority('course:teacher:manage')")
    public ApiResponse<TeacherCourse> create(@Valid @RequestBody TeacherCourseSaveRequest request) {
        return ApiResponse.ok(teacherCourseService.create(request));
    }

    /** 删除任课关系（逻辑删除，W9） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('course:teacher:manage')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        teacherCourseService.delete(id);
        return ApiResponse.ok();
    }
}
