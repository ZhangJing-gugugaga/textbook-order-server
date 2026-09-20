package com.tian.textbook.textbook.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.textbook.dto.CourseSaveRequest;
import com.tian.textbook.textbook.dto.CourseUpdateRequest;
import com.tian.textbook.textbook.entity.Course;
import com.tian.textbook.textbook.service.CourseService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 课程维护（SPEC §11.3 契约基线，/api/admin/course/**）。
 */
@RestController
@RequestMapping("/api/admin/course")
@RequiredArgsConstructor
public class AdminCourseController {

    private final CourseService courseService;

    /** 课程列表（semesterId 缺省 = 当前 active 学期） */
    @GetMapping
    @PreAuthorize("hasAuthority('course:course:manage')")
    public ApiResponse<List<Course>> list(@RequestParam(required = false) Long semesterId) {
        return ApiResponse.ok(courseService.list(semesterId));
    }

    /** 新增课程（semesterId 缺省 = active；code 非空时同学期查重） */
    @PostMapping
    @PreAuthorize("hasAuthority('course:course:manage')")
    public ApiResponse<Course> create(@Valid @RequestBody CourseSaveRequest request) {
        return ApiResponse.ok(courseService.create(request));
    }

    /** 编辑课程（仅 code/name；学期归属不可改） */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('course:course:manage')")
    public ApiResponse<Course> update(@PathVariable Long id, @Valid @RequestBody CourseUpdateRequest request) {
        return ApiResponse.ok(courseService.update(id, request));
    }
}
