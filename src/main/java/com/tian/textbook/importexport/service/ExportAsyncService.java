package com.tian.textbook.importexport.service;

import com.tian.textbook.common.util.AppTime;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.config.AsyncConfig;
import com.tian.textbook.common.config.TextbookProperties;
import com.tian.textbook.common.util.FailureMessages;
import com.tian.textbook.importexport.entity.ExportTask;
import com.tian.textbook.importexport.mapper.ExportTaskMapper;
import com.tian.textbook.system.config.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 异步导出执行器（SPEC §10：queued → running → done/failed）。
 *
 * <p>文件写入临时目录（textbook.export.tmp-dir，不存在则创建）；完成时发放一次性
 * download_token（UUID，默认 10 分钟过期）与 expires_at（保留 24 小时）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportAsyncService {

    private final ExportTaskMapper exportTaskMapper;
    private final ExportDataWriter dataWriter;
    private final TextbookProperties properties;
    private final ConfigService configService;

    @Async(AsyncConfig.EXPORT_EXECUTOR)
    public void generate(Long taskId) {
        ExportTask task = exportTaskMapper.selectByIdSoft(taskId);
        if (task == null) {
            return;
        }
        try {
            exportTaskMapper.update(null, Wrappers.<ExportTask>lambdaUpdate()
                    .eq(ExportTask::getId, taskId)
                    .set(ExportTask::getStatus, "running")
                    .set(ExportTask::getProgressPct, 10));

            Path dir = Path.of(properties.getExport().getTmpDir());
            Files.createDirectories(dir);
            Path target = dir.resolve("export-" + task.getBizType() + "-" + taskId + ".xlsx");
            try (OutputStream out = Files.newOutputStream(target)) {
                dataWriter.write(task.getBizType(), task.getParamsJson(), out);
            }

            LocalDateTime now = AppTime.now();
            int tokenMinutes = configService.getInt(ConfigService.EXPORT_DOWNLOAD_TOKEN_MINUTES,
                    properties.getExport().getDownloadTokenMinutes());
            int retentionHours = properties.getExport().getRetentionHours();
            exportTaskMapper.update(null, Wrappers.<ExportTask>lambdaUpdate()
                    .eq(ExportTask::getId, taskId)
                    .set(ExportTask::getStatus, "done")
                    .set(ExportTask::getProgressPct, 100)
                    .set(ExportTask::getFilePath, target.toString())
                    .set(ExportTask::getDownloadToken, UUID.randomUUID().toString())
                    .set(ExportTask::getTokenExpireAt, now.plusMinutes(tokenMinutes))
                    .set(ExportTask::getExpiresAt, now.plusHours(retentionHours)));
            // 只记录文件名，不打印服务器绝对路径（日志会外发/长期留存，路径属内部信息）
            log.info("异步导出完成: taskId={}, bizType={}, file={}", taskId, task.getBizType(),
                    target.getFileName());
        } catch (Exception e) {
            log.error("异步导出失败: taskId={}, bizType={}", taskId, task.getBizType(), e);
            exportTaskMapper.update(null, Wrappers.<ExportTask>lambdaUpdate()
                    .eq(ExportTask::getId, taskId)
                    .set(ExportTask::getStatus, "failed")
                    // 只透传业务异常文案：原始异常消息可能含 SQL/表名/服务器绝对路径
                    .set(ExportTask::getErrorMsg, FailureMessages.userFacing(e, "导出失败，请重试或联系教材室")));
        }
    }
}
