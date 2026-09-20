package com.tian.textbook.supplier;

import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.importexport.ExportService;
import com.tian.textbook.importexport.entity.ExportTask;
import com.tian.textbook.supplier.dto.SupplierCollegeGroup;
import com.tian.textbook.supplier.dto.SupplierExportRequest;
import com.tian.textbook.supplier.dto.SupplierOrderItem;
import com.tian.textbook.supplier.dto.SupplierOrderRow;
import com.tian.textbook.supplier.mapper.SupplierOrderMapper;
import com.tian.textbook.system.audit.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供货商只读服务（SPEC §11.5 · PRD 模块 8）。
 *
 * <p>物理隔离（SPEC §2 机检红线）：本包不 import 学生/教师相关 Mapper，查询一律走包内
 * 自建 {@link SupplierOrderMapper}（SQL 直连 join）。数据范围 = active 学期 reviewed
 * 教师征订明细，不含学生选购数据（W18）。</p>
 *
 * <p>导出异步阈值同导出中心（Q16：预估行数 > export.sync_row_threshold 走 export_task），
 * 每次导出写审计（谁/何时/范围）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierService {

    /** 导出 bizType（export_task.biz_type 白名单，DDL COMMENT） */
    public static final String BIZ_TYPE = "supplier";

    /** 同步导出内容类型（xlsx） */
    public static final String XLSX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final SupplierOrderMapper supplierOrderMapper;
    private final ExportService exportService;
    private final AuditService auditService;

    /** 按学院分组的 reviewed 清单（semesterId 为空取当前 active 学期） */
    @Transactional(readOnly = true)
    public List<SupplierCollegeGroup> listOrders(Long semesterId) {
        Long semester = requireSemester(semesterId);
        List<SupplierOrderRow> rows = supplierOrderMapper.selectReviewedRows(semester);
        Map<Long, SupplierCollegeGroup> groups = new LinkedHashMap<>();
        for (SupplierOrderRow row : rows) {
            SupplierCollegeGroup group = groups.computeIfAbsent(row.getCollegeId(), key -> {
                SupplierCollegeGroup created = new SupplierCollegeGroup();
                created.setCollegeId(row.getCollegeId());
                created.setCollegeName(row.getCollegeName());
                created.setItems(new ArrayList<>());
                return created;
            });
            SupplierOrderItem item = new SupplierOrderItem();
            item.setTeacherName(row.getTeacherName());
            item.setIsbn(row.getIsbn());
            item.setTitle(row.getTitle());
            item.setQuantity(row.getQuantity());
            group.getItems().add(item);
        }
        return new ArrayList<>(groups.values());
    }

    /**
     * 导出计划判定（一学院一 sheet，由导出中心按 bizType=supplier 生成）：
     * 预估行数 > export.sync_row_threshold → 建 export_task 返回 taskId（异步）；
     * ≤ 阈值 → 返回 null，调用方设置响应头后调 {@link #writeSync}。
     */
    @Transactional
    public Long planExport(SupplierExportRequest request) {
        Long semester = requireSemester(request.semesterId());
        long estimate = supplierOrderMapper.countReviewed(semester);
        int rowEstimate = (int) Math.min(estimate, Integer.MAX_VALUE);
        if (!exportService.shouldGoAsync(rowEstimate)) {
            return null;
        }
        ExportTask task = exportService.createAsyncTask(BIZ_TYPE, Map.of("semesterId", semester), rowEstimate);
        auditService.record(AuditService.EXPORT, BIZ_TYPE, String.valueOf(task.getId()),
                Map.of("op", "export", "mode", "async", "semesterId", String.valueOf(semester),
                        "rows", String.valueOf(estimate)));
        log.info("供货商导出（异步）: task={}, semester={}, 预估行数={}", task.getId(), semester, estimate);
        return task.getId();
    }

    /** 同步流式导出（响应头由 Controller 事先设置，Service 只写 OutputStream） */
    @Transactional
    public void writeSync(SupplierExportRequest request, OutputStream outputStream) throws IOException {
        Long semester = requireSemester(request.semesterId());
        long estimate = supplierOrderMapper.countReviewed(semester);
        exportService.writeSync(BIZ_TYPE, Map.of("semesterId", semester), outputStream);
        outputStream.flush();
        auditService.record(AuditService.EXPORT, BIZ_TYPE, null,
                Map.of("op", "export", "mode", "sync", "semesterId", String.valueOf(semester),
                        "rows", String.valueOf(estimate)));
        log.info("供货商导出（同步）: semester={}, 行数={}", semester, estimate);
    }

    /** 导出任务进度 */
    @Transactional(readOnly = true)
    public ExportTask getTask(Long taskId) {
        ExportTask task = exportService.getTask(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "导出任务不存在");
        }
        return task;
    }

    /**
     * 一次性下载 token 校验并消费（单次有效：首次下载后置空；过期/失效 → 410，
     * 由 ExportService.claimDownload 抛出）。
     */
    @Transactional(readOnly = true)
    public ExportTask claimDownload(Long taskId, String token) {
        return exportService.claimDownload(taskId, token);
    }

    private Long requireSemester(Long semesterId) {
        if (semesterId != null) {
            return semesterId;
        }
        Long active = SemesterContextHolder.get();
        if (active == null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "当前无激活学期");
        }
        return active;
    }
}
