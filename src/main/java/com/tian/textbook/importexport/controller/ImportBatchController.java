package com.tian.textbook.importexport.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.importexport.ImportService;
import com.tian.textbook.importexport.entity.ImportBatch;
import com.tian.textbook.importexport.support.DownloadSupport;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 导入批次进度与错误明细（SPEC §11.3：import:batch:view）。
 */
@RestController
@RequestMapping("/api/batch")
@RequiredArgsConstructor
public class ImportBatchController {

    private final ImportService importService;

    /** 批次进度（total/ok/error/progress_pct/status；归属校验：非 ADMIN 仅本人发起的批次） */
    @GetMapping("/{batchId}")
    @PreAuthorize("hasAuthority('import:batch:view')")
    public ApiResponse<ImportBatch> getBatch(@PathVariable Long batchId) {
        return ApiResponse.ok(importService.getBatchForUser(batchId));
    }

    /** 错误明细 xlsx 下载（无错误行 → 404；归属校验同上） */
    @GetMapping("/{batchId}/errors")
    @PreAuthorize("hasAuthority('import:batch:view')")
    public void downloadErrors(@PathVariable Long batchId, HttpServletResponse response) throws IOException {
        // 归属校验 + 文件存在性/过期提示都在 Service（Controller 只解析 HTTP）
        String filePath = importService.errorFilePathForDownload(batchId);
        DownloadSupport.writeFile(response, Path.of(filePath), "导入错误明细-" + batchId + ".xlsx");
    }
}
