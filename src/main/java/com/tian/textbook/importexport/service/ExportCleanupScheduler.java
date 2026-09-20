package com.tian.textbook.importexport.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.importexport.entity.ExportTask;
import com.tian.textbook.importexport.mapper.ExportTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 导出文件定时清理（SPEC §10/§15：文件保留 24 小时，到点删文件并置 status='expired'）。
 *
 * <p>每小时整点执行（Asia/Shanghai）；文件不存在忽略（幂等）。
 * test profile 下关闭（测试直接调清理方法，保证确定性）。</p>
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ExportCleanupScheduler {

    private final ExportTaskMapper exportTaskMapper;

    @Scheduled(cron = "0 0 * * * ?", zone = "Asia/Shanghai")
    public void cleanExpiredExports() {
        List<ExportTask> expired = exportTaskMapper.selectExpired(LocalDateTime.now());
        for (ExportTask task : expired) {
            try {
                if (task.getFilePath() != null) {
                    Files.deleteIfExists(Path.of(task.getFilePath()));
                }
                exportTaskMapper.update(null, Wrappers.<ExportTask>lambdaUpdate()
                        .eq(ExportTask::getId, task.getId())
                        .set(ExportTask::getStatus, "expired"));
                log.info("导出文件已清理: taskId={}, file={}", task.getId(), task.getFilePath());
            } catch (Exception e) {
                log.warn("导出文件清理失败: taskId={}", task.getId(), e);
            }
        }
    }
}
