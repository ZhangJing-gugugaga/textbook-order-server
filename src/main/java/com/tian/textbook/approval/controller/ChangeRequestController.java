package com.tian.textbook.approval.controller;

import com.tian.textbook.approval.dto.ChangeImportResult;
import com.tian.textbook.approval.dto.ChangeRequestVO;
import com.tian.textbook.approval.dto.ChangeSubmitRequest;
import com.tian.textbook.approval.service.ChangeRequestService;
import com.tian.textbook.common.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 异动提交端（SPEC §11.4 契约基线：教师/秘书逐条提交、秘书批量导入、我的提交记录）。
 */
@RestController
@RequiredArgsConstructor
public class ChangeRequestController {

    private final ChangeRequestService changeRequestService;

    /** 逐条提交异动（教师） */
    @PostMapping("/api/teacher/change")
    @PreAuthorize("hasAuthority('change:request:submit')")
    public ApiResponse<ChangeRequestVO> submitByTeacher(@Valid @RequestBody ChangeSubmitRequest request) {
        return ApiResponse.ok(changeRequestService.submit(request));
    }

    /** 逐条提交异动（秘书） */
    @PostMapping("/api/secretary/change")
    @PreAuthorize("hasAuthority('change:request:submit')")
    public ApiResponse<ChangeRequestVO> submitBySecretary(@Valid @RequestBody ChangeSubmitRequest request) {
        return ApiResponse.ok(changeRequestService.submit(request));
    }

    /** 批量导入（同步解析，返回 batchId + batchNo） */
    @PostMapping("/api/secretary/change/import")
    @PreAuthorize("hasAuthority('change:request:submit')")
    public ApiResponse<ChangeImportResult> importRows(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(changeRequestService.importRows(file));
    }

    /** 我的提交记录（含目标用户姓名/学院名，字段审查未通过的可见原因） */
    @GetMapping("/api/teacher/change")
    @PreAuthorize("hasAuthority('change:request:submit')")
    public ApiResponse<List<ChangeRequestVO>> myRequests() {
        return ApiResponse.ok(changeRequestService.myRequests());
    }

    /**
     * 异动提交端的目标归属选项（学院 / 班级）。
     *
     * <p>提交异动需选目标学院与目标班级，但组织三表接口均为超管权限（org:*:manage），
     * 教师/秘书调用会 403。此处按最小权限开放只读选项（id + 名称，不含人数等管理字段）。</p>
     */
    @GetMapping("/api/change/org-options")
    @PreAuthorize("hasAuthority('change:request:submit')")
    public ApiResponse<Map<String, Object>> orgOptions() {
        return ApiResponse.ok(changeRequestService.orgOptions());
    }
}
