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

    private final ImportRunContext ctx;
    private final ImportBatchMapper batchMapper;
    private final RowValidator<T> validator;
    private final RowFlusher<T> flusher;
    private final BiConsumer<ImportRunContext, ImportRunSummary> finalizer;

    private final List<Map<String, Object>> errors = new ArrayList<>();
    private final List<NumberedRow<T>> buffer = new ArrayList<>();
    private int dataRows;
    private int okRows;

    public ImportReadListener(ImportRunContext ctx, ImportBatchMapper batchMapper, RowValidator<T> validator,
                              RowFlusher<T> flusher, BiConsumer<ImportRunContext, ImportRunSummary> finalizer) {
        this.ctx = ctx;
        this.batchMapper = batchMapper;
        this.validator = validator;
        this.flusher = flusher;
        this.finalizer = finalizer;
    }

    @Override
    public void invoke(T data, AnalysisContext context) {
        int excelRow = currentRow(context);
        dataRows++;
        String error = validator.validate(data, excelRow, ctx);
        if (error != null) {
            errors.add(errorEntry(excelRow, error));
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
        errors.add(errorEntry(excelRow, "数据解析失败：" + exception.getMessage()));
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        flush();
        finalizer.accept(ctx, new ImportRunSummary(dataRows, okRows, errors));
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
            okRows += batch.size();
        } catch (Exception e) {
            // 整批回滚（事务在 writer 内），错误行继续收集（SPEC §12）
            for (NumberedRow<T> row : batch) {
                errors.add(errorEntry(row.excelRow(), "导入失败：" + e.getMessage()));
            }
        }
        updateProgress();
    }

    private void updateProgress() {
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
