package com.tian.textbook.importexport.service;

import com.tian.textbook.common.util.AppTime;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.config.TextbookProperties;
import com.tian.textbook.importexport.entity.ExportTask;
import com.tian.textbook.importexport.mapper.ExportTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 导出文件定时清理（SPEC §10/§15：文件保留 24 小时，到点删文件并置 status='expired'）。
 *
 * <p>每小时整点执行（Asia/Shanghai）；文件不存在忽略（幂等）。
 * test profile 下关闭（测试直接调清理方法，保证确定性）。</p>
 *
 * <p>同时清理<b>孤儿文件</b>：异步任务写盘后进程崩溃、任务行已逻辑删除等情况下，
 * 临时目录会留下没有任何任务引用的 xlsx。按「文件 mtime 早于保留期」清理，
 * 避免磁盘被无声占满。</p>
 */
@Slf4j
@Component
@Profile("!test")
@RequiredArgsConstructor
public class ExportCleanupScheduler {

    private final ExportTaskMapper exportTaskMapper;
    private final TextbookProperties properties;

    @Scheduled(cron = "0 0 * * * ?", zone = "Asia/Shanghai")
    public void cleanExpiredExports() {
        List<ExportTask> expired = exportTaskMapper.selectExpired(AppTime.now());
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
        cleanOrphanFiles();
    }

    /**
     * 清理导出/导入临时目录下超过保留期的孤儿文件。
     *
     * <p>按 mtime 判定：正常文件的 mtime 就是生成时刻，早于保留期即已过期。
     * 只扫描已知的三类产物目录（导出根目录、导入上传、导入错误明细）。</p>
     */
    void cleanOrphanFiles() {
        int retentionHours = properties.getExport().getRetentionHours();
        Instant cutoff = AppTime.now().minusHours(retentionHours).atZone(AppTime.ZONE).toInstant();
        Path root = Path.of(properties.getExport().getTmpDir());
        for (String sub : new String[]{"", "import", "import-errors"}) {
            Path dir = sub.isEmpty() ? root : root.resolve(sub);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(dir)) {
                List<Path> stale = files
                        .filter(Files::isRegularFile)
                        .filter(path -> isOlderThan(path, cutoff))
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
                for (Path path : stale) {
                    try {
                        Files.deleteIfExists(path);
                        log.info("清理孤儿临时文件: {}", path);
                    } catch (IOException e) {
                        log.warn("孤儿文件删除失败: {}", path, e);
                    }
                }
            } catch (IOException e) {
                log.warn("扫描临时目录失败: {}", dir, e);
            }
        }
    }

    private boolean isOlderThan(Path path, Instant cutoff) {
        try {
            return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            return false;
        }
    }
}
