package com.tian.textbook.semester.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.semester.SemesterService;
import com.tian.textbook.semester.dto.SemesterActivateRequest;
import com.tian.textbook.semester.dto.SemesterCreateRequest;
import com.tian.textbook.semester.dto.SemesterUpdateRequest;
import com.tian.textbook.semester.dto.WindowExtendRequest;
import com.tian.textbook.semester.dto.WindowSetRequest;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.system.entity.AuditLog;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 学期与窗口管理（SPEC §11.2 契约基线，/api/admin/semester/**）。
 */
@RestController
@RequestMapping("/api/admin/semester")
@RequiredArgsConstructor
public class AdminSemesterController {

    private final SemesterService semesterService;

    /** 学期列表（draft/active/archived） */
    @GetMapping
    @PreAuthorize("hasAuthority('semester:semester:manage')")
    public ApiResponse<List<Semester>> list() {
        return ApiResponse.ok(semesterService.list());
    }

    /** 新建学期（draft） */
    @PostMapping
    @PreAuthorize("hasAuthority('semester:semester:manage')")
    public ApiResponse<Semester> create(@Valid @RequestBody SemesterCreateRequest request) {
        return ApiResponse.ok(semesterService.create(request));
    }

    /** 学期详情 */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('semester:semester:manage')")
    public ApiResponse<Semester> get(@PathVariable Long id) {
        return ApiResponse.ok(semesterService.get(id));
    }

    /** 编辑基本信息 */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('semester:semester:manage')")
    public ApiResponse<Semester> update(@PathVariable Long id, @Valid @RequestBody SemesterUpdateRequest request) {
        return ApiResponse.ok(semesterService.updateBasic(id, request));
    }

    /** 双缓冲原子切换（body 带 version） */
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('semester:semester:activate')")
    public ApiResponse<Semester> activate(@PathVariable Long id, @Valid @RequestBody SemesterActivateRequest request) {
        return ApiResponse.ok(semesterService.activate(id, request));
    }

    /** 归档 */
    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('semester:semester:activate')")
    public ApiResponse<Void> archive(@PathVariable Long id) {
        semesterService.archive(id);
        return ApiResponse.ok();
    }

    /** 设置起止 + auto 开关 */
    @PutMapping("/{id}/window")
    @PreAuthorize("hasAuthority('semester:window:manage')")
    public ApiResponse<Semester> setWindow(@PathVariable Long id, @Valid @RequestBody WindowSetRequest request) {
        return ApiResponse.ok(semesterService.setWindow(id, request));
    }

    /** 手动开启 */
    @PostMapping("/{id}/window/open")
    @PreAuthorize("hasAuthority('semester:window:manage')")
    public ApiResponse<Semester> openWindow(@PathVariable Long id) {
        return ApiResponse.ok(semesterService.openWindow(id));
    }

    /** 提前截止 */
    @PostMapping("/{id}/window/close")
    @PreAuthorize("hasAuthority('semester:window:manage')")
    public ApiResponse<Semester> closeWindow(@PathVariable Long id) {
        return ApiResponse.ok(semesterService.closeWindow(id));
    }

    /** 延长（无限次） */
    @PostMapping("/{id}/window/extend")
    @PreAuthorize("hasAuthority('semester:window:manage')")
    public ApiResponse<Semester> extendWindow(@PathVariable Long id, @Valid @RequestBody WindowExtendRequest request) {
        return ApiResponse.ok(semesterService.extendWindow(id, request));
    }

    /** 窗口变更记录（谁/何时/原值→新值，W24） */
    @GetMapping("/{id}/window/changes")
    @PreAuthorize("hasAuthority('semester:window:manage')")
    public ApiResponse<PageResponse<AuditLog>> windowChanges(@PathVariable Long id,
                                                             @RequestParam(defaultValue = "1") long page,
                                                             @RequestParam(defaultValue = "20") long size) {
        List<AuditLog> records = semesterService.windowChanges(id, page, Math.min(size, 200));
        return ApiResponse.ok(PageResponse.of(records, page, size, records.size()));
    }
}
