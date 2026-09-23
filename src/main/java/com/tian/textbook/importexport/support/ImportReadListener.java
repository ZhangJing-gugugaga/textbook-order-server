package com.tian.textbook.importexport.support;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.importexport.entity.ImportBatch;
import com.tian.textbook.importexport.mapper.ImportBatchMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 通用导入读取监听器（SPEC §10：EasyExcel 流式解析 → 每 500 行批量 upsert →
 * 错误行收集不中断 → 完成写 ok/error 计数）。
 *
 * <p>行校验（外键/必填/格式）失败的行进入错误集，不中断解析；每 {@value #FLUSH_SIZE} 行
 * 调用一次 flusher（事务由 ImportRowWriter 的 REQUIRES_NEW 方法承载，失败仅回滚该批）。</p>
 *
 * @param <T> 行模型（StudentImportRow / TeacherImportRow / TextbookImportRow / TeacherCourseImportRow）
 */
public class ImportReadListener<T> extends AnalysisEventListener<T> {

    /** 每 500 行一个事务（SPEC §12） */
    public static final int FLUSH_SIZE = 500;

    /**
     * 错误明细条数上限：错误行全量保留会让 error_detail JSON 随行数线性膨胀
     * （一次全错的万行导入即写入上万条明细，既撑爆列宽也拖垮批次查询）。
     * 超出部分只计数不保留明细，收尾摘要给出「已截断」标记。
     */
    public static final int MAX_ERROR_DETAIL = 500;

    /** 行校验：返回 null 通过，否则返回错误文案（同时可向 ctx 记录停用比对范围） */
    @FunctionalInterface
    public interface RowValidator<T> {
        String validate(T row, int excelRow, ImportRunContext ctx);
    }

    /** 批量落库（ImportRowWriter 事务方法） */
    @FunctionalInterface
    public interface RowFlusher<T> {
        void flush(List<T> rows, ImportRunContext ctx);
    }

    /**
     * 已落库行的业务结论（可选）：某些导入类型「行落库了但仍算错误行」。
     *
     * <p>异动导入（BE-7b）即如此：字段审查不通过的行会以 {@code rejected} 落库（申请人可见原因），
     * 同时要计入批次错误明细供下载核对——只靠 validator 返回值无法表达「既不通过校验、
     * 又必须写入」这一组合。</p>
     */
    @FunctionalInterface
    public interface RowOutcome<T> {
        /** @return 计为错误行的文案；null = 正常成功行 */
        String errorMessage(T row);
    }

    private final ImportRunContext ctx;
    private final ImportBatchMapper batchMapper;
    private final RowValidator<T> validator;
    private final RowFlusher<T> flusher;
    private final BiConsumer<ImportRunContext, ImportRunSummary> finalizer;
    /** 已落库行的业务结论（可选）：返回错误文案的行计入错误明细，但数据已落库 */
    private final RowOutcome<T> outcome;

    private final List<Map<String, Object>> errors = new ArrayList<>();
    private final List<NumberedRow<T>> buffer = new ArrayList<>();
    private int dataRows;
    private int okRows;
    /** 被上限截断、只计数未保留明细的错误行数 */
    private int truncatedErrors;

    public ImportReadListener(ImportRunContext ctx, ImportBatchMapper batchMapper, RowValidator<T> validator,
                              RowFlusher<T> flusher, BiConsumer<ImportRunContext, ImportRunSummary> finalizer) {
        this(ctx, batchMapper, validator, flusher, finalizer, null);
    }

    public ImportReadListener(ImportRunContext ctx, ImportBatchMapper batchMapper, RowValidator<T> validator,
                              RowFlusher<T> flusher, BiConsumer<ImportRunContext, ImportRunSummary> finalizer,
                              RowOutcome<T> outcome) {
        this.ctx = ctx;
        this.batchMapper = batchMapper;
        this.validator = validator;
        this.flusher = flusher;
        this.finalizer = finalizer;
        this.outcome = outcome;
    }

    @Override
    public void invoke(T data, AnalysisContext context) {
        int excelRow = currentRow(context);
        dataRows++;
        String error = validator.validate(data, excelRow, ctx);
        if (error != null) {
            addError(errorEntry(excelRow, error));
            return;
        }
        buffer.add(new NumberedRow<>(excelRow, data));
        if (buffer.size() >= FLUSH_SIZE) {
            flush();
        }
    }

    @Override
    public void onException(Exception exception, AnalysisContext context) throws Exception {
        // 单元格转换等异常：记录行错误后继续解析（不中断，SPEC §10）
        int excelRow = context.readRowHolder() == null ? 0 : context.readRowHolder().getRowIndex() + 1;
        dataRows++;
        addError(errorEntry(excelRow, "数据解析失败：" + exception.getMessage()));
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        flush();
        finalizer.accept(ctx, new ImportRunSummary(dataRows, okRows, errors, truncatedErrors));
    }

    private void flush() {
        if (buffer.isEmpty()) {
            updateProgress();
            return;
        }
        List<NumberedRow<T>> batch = new ArrayList<>(buffer);
        buffer.clear();
        try {
            flusher.flush(batch.stream().map(NumberedRow::data).toList(), ctx);
            for (NumberedRow<T> row : batch) {
                String businessError = outcome == null ? null : outcome.errorMessage(row.data());
                if (businessError == null) {
                    okRows++;
                } else {
                    addError(errorEntry(row.excelRow(), businessError));
                }
            }
        } catch (Exception e) {
            // 整批回滚（事务在 writer 内），错误行继续收集（SPEC §12）
            for (NumberedRow<T> row : batch) {
                addError(errorEntry(row.excelRow(), "导入失败：" + e.getMessage()));
            }
        }
        updateProgress();
    }

    /** 错误明细受 {@link #MAX_ERROR_DETAIL} 约束；超限只累加计数。 */
    private void addError(Map<String, Object> entry) {
        if (errors.size() < MAX_ERROR_DETAIL) {
            errors.add(entry);
        } else {
            truncatedErrors++;
        }
    }

    private void updateProgress() {
        if (ctx.batchId() == null) {
            // 只读扫描（导入预览 / 局部名单门禁）没有批次行：跳过进度写。
            // 必须显式跳过——MyBatis-Plus 的 update(wrapper.eq(id, null)) 会退化成不带 where 的
            // 全表更新，把 import_batch 所有批次的进度一起改掉。
            return;
        }
        // total 未知：按已处理行数线性估计（每 100 行 1%，封顶 99%，完成时置 100）
        int pct = Math.min(99, dataRows / 100);
        batchMapper.update(null, Wrappers.<ImportBatch>lambdaUpdate()
                .eq(ImportBatch::getId, ctx.batchId())
                .set(ImportBatch::getProgressPct, pct));
    }

    private int currentRow(AnalysisContext context) {
        return context.readRowHolder() == null ? 0 : context.readRowHolder().getRowIndex() + 1;
    }

    private Map<String, Object> errorEntry(int row, String message) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("row", row);
        entry.put("message", message);
        return entry;
    }

    private record NumberedRow<T>(int excelRow, T data) {
    }
}
