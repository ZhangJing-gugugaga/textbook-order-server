package com.tian.textbook.importexport.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.importexport.dto.ExportPlan;
import com.tian.textbook.importexport.dto.NoticeExportRequest;
import com.tian.textbook.importexport.dto.OrderExportRequest;
import com.tian.textbook.importexport.dto.SignatureExportRequest;
import com.tian.textbook.importexport.dto.StudentExportRequest;
import com.tian.textbook.importexport.entity.ExportTask;
import com.tian.textbook.importexport.service.ExportServiceImpl;
import com.tian.textbook.importexport.support.DownloadSupport;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * 导出中心端点（SPEC §11.5）：同步流式下载 / 异步任务轮询 + 一次性 token 下载。
 *
 * <p>阈值判定（export.sync_row_threshold，默认 5000）在 Service：≤ 阈值直接写响应流，
 * > 阈值返回 taskId 供前端轮询 GET /api/export-task/{id}。</p>
 */
@RestController
@RequiredArgsConstructor
public class ExportController {

    private final ExportServiceImpl exportService;

    /** 教师征订明细导出（秘书本院 / 教材室全院） */
    @PostMapping("/api/admin/export/orders")
    @PreAuthorize("hasAuthority('export:order:create')")
    public ApiResponse<Map<String, Object>> exportOrders(
            @RequestBody(required = false) @Valid OrderExportRequest request,
            HttpServletResponse response) throws IOException {
        Long semesterId = request == null ? null : request.semesterId();
        Long collegeId = request == null ? null : request.collegeId();
        return dispatch(exportService.planOrderExport(semesterId, collegeId), response);
    }

    /** 秘书本院签字版导出（学院范围 = 当前用户 active 学期归属，W6） */
    @PostMapping("/api/secretary/export/signature")
    @PreAuthorize("hasAuthority('export:signature:create')")
    public ApiResponse<Map<String, Object>> exportSignature(
            @RequestBody(required = false) @Valid SignatureExportRequest request,
            HttpServletResponse response) throws IOException {
        Long semesterId = request == null ? null : request.semesterId();
        return dispatch(exportService.planSignatureExport(semesterId), response);
    }

    /** 学生选购汇总（参考用量，仅教材室） */
    @PostMapping("/api/admin/export/students")
    @PreAuthorize("hasAuthority('export:student:create')")
    public ApiResponse<Map<String, Object>> exportStudents(
            @RequestBody(required = false) @Valid StudentExportRequest request,
            HttpServletResponse response) throws IOException {
        Long semesterId = request == null ? null : request.semesterId();
        return dispatch(exportService.planStudentExport(semesterId), response);
    }

    /** 通知汇总导出 */
    @PostMapping("/api/admin/export/notice")
    @PreAuthorize("hasAuthority('export:notice:create')")
    public ApiResponse<Map<String, Object>> exportNotice(
            @RequestBody(required = false) @Valid NoticeExportRequest request,
            HttpServletResponse response) throws IOException {
        if (request == null || request.taskId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "通知任务不能为空");
        }
        return dispatch(exportService.planNoticeExport(request.taskId()), response);
    }

    /** 导出任务进度（归属校验：非 ADMIN 仅本人创建的任务） */
    @GetMapping("/api/export-task/{id}")
    public ApiResponse<ExportTask> getTask(@PathVariable Long id) {
        return ApiResponse.ok(exportService.getTaskForUser(id));
    }

    /** 一次性下载（token 单次有效、默认 10 分钟过期；失效 410） */
    @GetMapping("/api/export-task/{id}/download")
    public void download(@PathVariable Long id,
                         @RequestParam String token,
                         HttpServletResponse response) throws IOException {
        ExportTask task = exportService.claimDownloadForUser(id, token);
        DownloadSupport.writeFile(response, Path.of(task.getFilePath()), fileNameOf(task.getBizType()));
    }

    // ============ 私有 ============

    private ApiResponse<Map<String, Object>> dispatch(ExportPlan plan, HttpServletResponse response)
            throws IOException {
        if (plan.async()) {
            return ApiResponse.ok(Map.of("taskId", plan.taskId(), "async", true, "rowEstimate", plan.rowEstimate()));
        }
        DownloadSupport.attachXlsx(response, plan.fileName());
        exportService.writeSync(plan.bizType(), plan.params(), response.getOutputStream());
        return null;
    }

    private String fileNameOf(String bizType) {
        return switch (bizType) {
            case "order" -> "教师征订明细.xlsx";
            case "signature" -> "教材征订签字版.xlsx";
            case "student" -> "学生选购汇总.xlsx";
            case "notice" -> "通知汇总.xlsx";
            case "supplier" -> "供货商清单.xlsx";
            default -> "导出数据.xlsx";
        };
    }
}
