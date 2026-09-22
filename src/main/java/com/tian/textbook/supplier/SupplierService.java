package com.tian.textbook.supplier;

import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.importexport.ExportService;
import com.tian.textbook.importexport.dto.ExportPlan;
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

    /** 清单接口行数上限（超出请用导出中心；导出走流式，不受此限） */
    private static final int LIST_ROW_LIMIT = 20_000;

    /** 同步导出文件名（Content-Disposition；异步任务走轮询，不使用该值） */
    private static final String SYNC_FILE_NAME = "supplier-orders.xlsx";

    private final SupplierOrderMapper supplierOrderMapper;
    private final ExportService exportService;
    private final AuditService auditService;

    /** 按学院分组的 reviewed 清单（semesterId 为空取当前 active 学期） */
    @Transactional(readOnly = true)
    public List<SupplierCollegeGroup> listOrders(Long semesterId) {
        Long semester = requireSemester(semesterId);
        // 行数上限：清单是同步响应体，全量入内存无上限时大校单次请求即内存尖峰；
        // 超出部分请走导出中心（异步 + 流式，无此限制）
        List<SupplierOrderRow> rows = supplierOrderMapper.selectReviewedRows(semester, LIST_ROW_LIMIT);
        if (rows.size() >= LIST_ROW_LIMIT) {
            log.warn("供货商清单已达行数上限，结果被截断: semester={}, limit={}", semester, LIST_ROW_LIMIT);
        }
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
     * 预估行数 > export.sync_row_threshold → 建 export_task（异步），返回计划供 Controller 回
     * {@code {taskId, async, rowEstimate}}；≤ 阈值 → async=false，调用方设置响应头后调
     * {@link #writeSync}。
     *
     * <p>返回计划而非 taskId：其余四类导出的异步受理体都是 {@code {taskId, async, rowEstimate}}
     * （API.md §3.10），供货商此前只回 {@code {taskId}}——前端只能靠 Content-Type 分流才没踩到。
     * 同一份契约不该有两种形态。</p>
     */
    @Transactional
    public ExportPlan planExport(SupplierExportRequest request) {
        Long semester = requireSemester(request.semesterId());
        long estimate = supplierOrderMapper.countReviewed(semester);
        int rowEstimate = (int) Math.min(estimate, Integer.MAX_VALUE);
        Map<String, Object> params = Map.of("semesterId", semester);
        if (!exportService.shouldGoAsync(rowEstimate)) {
            return new ExportPlan(false, null, BIZ_TYPE, params, SYNC_FILE_NAME, rowEstimate);
        }
        ExportTask task = exportService.createAsyncTask(BIZ_TYPE, params, rowEstimate);
        auditService.record(AuditService.EXPORT, BIZ_TYPE, String.valueOf(task.getId()),
                Map.of("op", "export", "mode", "async", "semesterId", String.valueOf(semester),
                        "rows", String.valueOf(estimate)));
        log.info("供货商导出（异步）: task={}, semester={}, 预估行数={}", task.getId(), semester, estimate);
        return new ExportPlan(true, task.getId(), BIZ_TYPE, params, SYNC_FILE_NAME, rowEstimate);
    }

    /**
     * 同步流式导出（响应头由 Controller 事先设置，Service 只写 OutputStream）。
     *
     * <p>刻意不加 {@code @Transactional}：本方法把「xlsx 生成 + 网络传输」整体包在事务里时，
     * Hikari 连接要等到响应写完才释放（池上限 20）。任一供货商账号并发发起慢速下载
     * 即可耗尽连接池导致全站不可用。查询各自单语句，无需事务包裹。</p>
     */
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

    /**
     * 导出任务进度（归属 + bizType 双重校验）。
     *
     * <p>export_task 与内部导出任务共表且主键自增，仅按 id 取任务时任一供货商账号
     * 即可枚举读取内部任务（教师征订/学生选购/通知汇总）元数据。</p>
     */
    @Transactional(readOnly = true)
    public ExportTask getTask(Long taskId) {
        return exportService.getSupplierTask(taskId);
    }

    /**
     * 一次性下载 token 校验并消费（归属 + bizType 校验；单次有效：首次下载后置空；
     * 过期/失效 → 410，由 ExportService.claimDownload 抛出）。
     *
     * <p>不加 {@code @Transactional}：下载是「校验 + 单条 UPDATE」两步，
     * 原子性由 claimDownload 内的 token CAS 谓词保证，无需外层事务。</p>
     */
    public ExportTask claimDownload(Long taskId, String token) {
        return exportService.claimSupplierDownload(taskId, token);
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
