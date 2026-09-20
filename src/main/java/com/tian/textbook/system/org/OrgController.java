package com.tian.textbook.system.org;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.system.entity.College;
import com.tian.textbook.system.entity.Major;
import com.tian.textbook.system.entity.SchoolClass;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 组织三表维护（SPEC §11.3 契约基线）。
 */
@RestController
@RequiredArgsConstructor
public class OrgController {

    private final OrgService orgService;

    // ============ 学院 ============

    @GetMapping("/api/admin/college")
    @PreAuthorize("hasAuthority('org:college:manage')")
    public ApiResponse<List<College>> listColleges() {
        return ApiResponse.ok(orgService.listColleges());
    }

    @PostMapping("/api/admin/college")
    @PreAuthorize("hasAuthority('org:college:manage')")
    public ApiResponse<College> createCollege(@Valid @RequestBody CollegeRequest request) {
        return ApiResponse.ok(orgService.createCollege(request.name(), request.fullName()));
    }

    @PutMapping("/api/admin/college/{id}")
    @PreAuthorize("hasAuthority('org:college:manage')")
    public ApiResponse<College> updateCollege(@PathVariable Long id, @Valid @RequestBody CollegeRequest request) {
        return ApiResponse.ok(orgService.updateCollege(id, request.name(), request.fullName()));
    }

    // ============ 专业 ============

    @GetMapping("/api/admin/major")
    @PreAuthorize("hasAuthority('org:major:manage')")
    public ApiResponse<List<Major>> listMajors(@RequestParam(required = false) Long collegeId) {
        return ApiResponse.ok(orgService.listMajors(collegeId));
    }

    @PostMapping("/api/admin/major")
    @PreAuthorize("hasAuthority('org:major:manage')")
    public ApiResponse<Major> createMajor(@Valid @RequestBody MajorRequest request) {
        return ApiResponse.ok(orgService.createMajor(request.collegeId(), request.name(), request.fullName()));
    }

    @PutMapping("/api/admin/major/{id}")
    @PreAuthorize("hasAuthority('org:major:manage')")
    public ApiResponse<Major> updateMajor(@PathVariable Long id, @Valid @RequestBody MajorRequest request) {
        return ApiResponse.ok(orgService.updateMajor(id, request.name(), request.fullName()));
    }

    // ============ 行政班 ============

    @GetMapping("/api/admin/class")
    @PreAuthorize("hasAuthority('org:class:manage')")
    public ApiResponse<List<SchoolClass>> listClasses(@RequestParam(required = false) Long majorId) {
        return ApiResponse.ok(orgService.listClasses(majorId));
    }

    @PostMapping("/api/admin/class")
    @PreAuthorize("hasAuthority('org:class:manage')")
    public ApiResponse<SchoolClass> createClass(@Valid @RequestBody ClassRequest request) {
        return ApiResponse.ok(orgService.createClass(request.majorId(), request.name(), request.grade(), request.studentCount()));
    }

    @PutMapping("/api/admin/class/{id}")
    @PreAuthorize("hasAuthority('org:class:manage')")
    public ApiResponse<SchoolClass> updateClass(@PathVariable Long id, @Valid @RequestBody ClassRequest request) {
        return ApiResponse.ok(orgService.updateClass(id, request.name(), request.grade(), request.studentCount()));
    }

    public record CollegeRequest(@jakarta.validation.constraints.NotBlank(message = "学院名称不能为空") String name,
                                 String fullName) {
    }

    public record MajorRequest(@jakarta.validation.constraints.NotNull(message = "学院不能为空") Long collegeId,
                               @jakarta.validation.constraints.NotBlank(message = "专业名称不能为空") String name,
                               String fullName) {
    }

    public record ClassRequest(@jakarta.validation.constraints.NotNull(message = "专业不能为空") Long majorId,
                               @jakarta.validation.constraints.NotBlank(message = "班级名称不能为空") String name,
                               String grade,
                               Integer studentCount) {
    }
}
