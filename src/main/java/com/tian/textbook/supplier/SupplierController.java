package com.tian.textbook.supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.importexport.entity.ExportTask;
import com.tian.textbook.supplier.dto.SupplierCollegeGroup;
import com.tian.textbook.supplier.dto.SupplierExportRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 供货商只读接口（SPEC §11.5 · PRD 模块 8：/api/supplier/** 物理隔离）。
 *
 * <p>无数据隔离切面（独立包 + 独立前缀 + CI ArchUnit 机检 import 红线）；
 * 导出异步阈值同导出中心（>5000 行走 export_task + 轮询 + 一次性 token 下载，Q16）。</p>
 */
@RestController
@RequestMapping("/api/supplier")
@RequiredArgsConstructor
public class SupplierController {

    /** 同步/异步导出内容类型（xlsx） */
    private static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final SupplierService supplierService;
    private final ObjectMapper objectMapper;

    /** 按学院分组清单（书名/ISBN/数量/教师姓名/学院；semesterId 默认 active） */
    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('supplier:order:view')")
    public ApiResponse<List<SupplierCollegeGroup>> orders(@RequestParam(required = false) Long semesterId) {
        return ApiResponse.ok(supplierService.listOrders(semesterId));
    }

    /**
     * 导出（一学院一 sheet）：预估行数 ≤ 阈值 → 同步流式下载；> 阈值 → 异步，
     * 返回 {taskId} 供轮询 GET /api/supplier/export-task/{id}。每次导出写审计。
     *
     * <p>先判定导出方式：异步走 JSON 包络；同步先设响应头（头必须在 body 前）再写流。</p>
     */
    @PostMapping("/export")
    @PreAuthorize("hasAuthority('supplier:order:export')")
    public void export(@Valid @RequestBody SupplierExportRequest request,
                       HttpServletResponse response) throws IOException {
        Long taskId = supplierService.planExport(request);
        if (taskId != null) {
            // 异步：轮询 + 一次性 token 下载
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getOutputStream(), ApiResponse.ok(Map.of("taskId", taskId)));
            response.flushBuffer();
            return;
        }
        // 同步流式：头必须在 body 前设置
        response.setContentType(XLSX_CONTENT_TYPE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"supplier-orders.xlsx\"");
        supplierService.writeSync(request, response.getOutputStream());
        response.flushBuffer();
    }

    /** 导出任务进度 */
    @GetMapping("/export-task/{id}")
    @PreAuthorize("hasAuthority('supplier:order:export')")
    public ApiResponse<ExportTask> exportTask(@PathVariable Long id) {
        return ApiResponse.ok(supplierService.getTask(id));
    }

    /** 一次性 token 下载（单次有效，默认 10 分钟过期，失效 410） */
    @GetMapping("/export-task/{id}/download")
    @PreAuthorize("hasAuthority('supplier:order:export')")
    public void download(@PathVariable Long id,
                         @RequestParam String token,
                         HttpServletResponse response) throws IOException {
        ExportTask task = supplierService.claimDownload(id, token);
        Path file = Paths.get(task.getFilePath());
        if (!Files.isReadable(file)) {
            throw new BizException(ErrorCode.NOT_FOUND, "导出文件不存在或已清理");
        }
        String fileName = "supplier-orders-" + id + ".xlsx";
        response.setContentType(XLSX_CONTENT_TYPE);
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"");
        response.setContentLengthLong(Files.size(file));
        Files.copy(file, response.getOutputStream());
        response.flushBuffer();
    }
}
