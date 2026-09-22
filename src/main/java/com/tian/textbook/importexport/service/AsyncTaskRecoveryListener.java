package com.tian.textbook.importexport.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.config.TextbookProperties;
import com.tian.textbook.common.util.AppTime;
import com.tian.textbook.importexport.entity.ExportTask;
import com.tian.textbook.importexport.entity.ImportBatch;
import com.tian.textbook.importexport.mapper.ExportTaskMapper;
import com.tian.textbook.importexport.mapper.ImportBatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 异步任务重启补偿（P2）：把上次进程退出时遗留的 running/queued 任务转终态。
 *
 * <p>异步导入/导出在内存线程池执行，进程重启后这些任务永远不会再有执行者：
 * 前端会一直轮询到一个永远 running 的批次，且没有任何机制收敛它。
 * 启动时统一置 failed（error_msg 说明原因），让用户看到明确结果并重新发起。</p>
 *
 * <h2>多实例安全边界（R6）</h2>
 * <p>「启动即全量置 failed」只在<b>单实例</b>部署下正确。多实例滚动发布时（先启新实例、
 * 再停旧实例），新实例启动会把旧实例<b>正在执行</b>的任务误杀。为此本类做了两道收窄：</p>
 * <ol>
 *   <li><b>陈旧阈值</b>：只回收 {@code updated_at} 早于「本进程启动时刻」的记录。
 *       异步执行器在开始与结束时都会更新 {@code updated_at}，因此正在被任何实例推进的任务
 *       不会被误杀；本实例启动后新建的任务也不会被误杀（{@code ApplicationReadyEvent}
 *       在 Tomcat 已开始接收请求之后才发布，早期实现存在误杀新任务的窗口）。</li>
 *   <li><b>开关</b>：{@code textbook.async.recover-on-startup=false} 可整体关闭
 *       （多实例部署时应关闭，改由运维人工收敛或引入 owner_instance + 心跳）。</li>
 * </ol>
 * <p>彻底方案（本轮未实施，见 docs/SECURITY-FIX-2026-09-21.md 待确认清单）：
 * 任务表增加 {@code owner_instance} 列 + 实例心跳，按「属主实例已失联」精确回收。</p>
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class AsyncTaskRecoveryListener {

    /** 重启后判定为「遗留」的导出任务状态 */
    private static final List<String> EXPORT_INFLIGHT = List.of("queued", "running");
    /** 重启后判定为「遗留」的导入批次状态 */
    private static final List<String> IMPORT_INFLIGHT = List.of("running");

    /** 本进程启动时刻（类加载即容器启动早期，早于任何请求进入） */
    private static final LocalDateTime PROCESS_STARTED_AT = AppTime.now();

    private final ExportTaskMapper exportTaskMapper;
    private final ImportBatchMapper importBatchMapper;
    private final TextbookProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        if (!properties.getAsync().isRecoverOnStartup()) {
            log.info("异步任务启动补偿已关闭（textbook.async.recover-on-startup=false）");
            return;
        }
        int exports = recoverExportTasks();
        int imports = recoverImportBatches();
        if (exports + imports > 0) {
            log.warn("启动补偿：{} 个导出任务、{} 个导入批次因进程重启被标记为 failed"
                            + "（仅回收 {} 之前未再更新的记录）",
                    exports, imports, PROCESS_STARTED_AT);
        }
    }

    private int recoverExportTasks() {
        List<ExportTask> inflight = exportTaskMapper.selectList(Wrappers.<ExportTask>lambdaQuery()
                .in(ExportTask::getStatus, EXPORT_INFLIGHT)
                .lt(ExportTask::getUpdatedAt, PROCESS_STARTED_AT)
                .eq(ExportTask::getDeleted, 0));
        for (ExportTask task : inflight) {
            exportTaskMapper.update(null, Wrappers.<ExportTask>lambdaUpdate()
                    .eq(ExportTask::getId, task.getId())
                    .in(ExportTask::getStatus, EXPORT_INFLIGHT)
                    .set(ExportTask::getStatus, "failed")
                    .set(ExportTask::getErrorMsg, "服务重启导致导出任务中断，请重新导出"));
        }
        return inflight.size();
    }

    private int recoverImportBatches() {
        List<ImportBatch> inflight = importBatchMapper.selectList(Wrappers.<ImportBatch>lambdaQuery()
                .in(ImportBatch::getStatus, IMPORT_INFLIGHT)
                .lt(ImportBatch::getUpdatedAt, PROCESS_STARTED_AT)
                .eq(ImportBatch::getDeleted, 0));
        for (ImportBatch batch : inflight) {
            importBatchMapper.update(null, Wrappers.<ImportBatch>lambdaUpdate()
                    .eq(ImportBatch::getId, batch.getId())
                    .in(ImportBatch::getStatus, IMPORT_INFLIGHT)
                    .set(ImportBatch::getStatus, "failed")
                    .set(ImportBatch::getProgressPct, 100)
                    .set(ImportBatch::getErrorDetail, List.of(
                            java.util.Map.of("row", 0, "message", "服务重启导致导入中断，请重新上传"))));
        }
        return inflight.size();
    }
}
