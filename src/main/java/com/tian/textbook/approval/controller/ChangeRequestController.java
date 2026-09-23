package com.tian.textbook.approval.controller;

import com.tian.textbook.approval.dto.ChangeRequestVO;
import com.tian.textbook.approval.dto.ChangeSubmitRequest;
import com.tian.textbook.approval.service.ChangeRequestService;
import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.annotation.AuditLog;
import com.tian.textbook.importexport.ImportService;
import com.tian.textbook.importexport.dto.BatchStartResponse;
import com.tian.textbook.importexport.support.DownloadSupport;
import com.tian.textbook.importexport.template.TemplateService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 异动提交端（SPEC §11.4 契约基线：教师/秘书逐条提交、秘书批量导入、我的提交记录）。
 */
@RestController
@RequiredArgsConstructor
public class ChangeRequestController {

    private final ChangeRequestService changeRequestService;
    private final ImportService importService;
    private final TemplateService templateService;

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

    /**
     * 批量导入（BE-7b：异步批次）。
     *
     * <p>响应由「同步结果体 {batchId, batchNo, total, okCount, errorCount}」改为
     * **{@code {batchId}}**，进度与错误明细走既有导入批次端点
     * （{@code GET /api/batch/{id}}、{@code GET /api/batch/{id}/errors}）——与其余四类导入一致。
     * 逐行的字段审查结果仍可在「我的提交记录」与审批列表按 batchNo 查看。</p>
     */
    @AuditLog(action = "IMPORT", resource = "import_batch")
    @PostMapping("/api/secretary/change/import")
    @PreAuthorize("hasAuthority('change:request:submit')")
    public ApiResponse<BatchStartResponse> importRows(@RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(new BatchStartResponse(importService.startImport("change", null, file)));
    }

    /**
     * 异动名单导入模板下载（BE-7c）：6 列（学号/工号、异动对象、目标学院、目标班级、原因、异动类型）。
     *
     * <p>模板生成能力（{@code TemplateService.writeChange}）此前已存在，只是没有端点，
     * 秘书只能手工造表头——列顺序一旦写错，导入会整批落 rejected。</p>
     */
    @GetMapping("/api/secretary/change/template")
    @PreAuthorize("hasAuthority('change:request:submit')")
    public void template(HttpServletResponse response) throws IOException {
        DownloadSupport.attachXlsx(response, "异动名单导入模板.xlsx");
        templateService.writeChange(response.getOutputStream());
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
