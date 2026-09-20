package com.tian.textbook.importexport.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.config.TextbookProperties;
import com.tian.textbook.common.CurrentUser;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.common.SecurityUtils;
import com.tian.textbook.importexport.ImportService;
import com.tian.textbook.importexport.entity.ImportBatch;
import com.tian.textbook.importexport.mapper.ImportBatchMapper;
import com.tian.textbook.importexport.support.ImportUploadValidator;
import com.tian.textbook.semester.SemesterActiveService;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.system.config.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 导入中心门面（SPEC §10 / §11.3）：上传校验 → 落盘 → 批次落库 → 异步解析。
 *
 * <p>事务边界：本类无事务（批次 insert 单语句即时提交，保证异步线程立即可见）；
 * 行级 upsert 事务在 {@link ImportRowWriter}（每 500 行一个 REQUIRES_NEW 事务）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportServiceImpl implements ImportService {

    private static final Set<String> SUPPORTED_BIZ_TYPES =
            Set.of("student", "teacher", "textbook", "teacher_course", "change");

    private final ImportBatchMapper importBatchMapper;
    private final ImportAsyncService importAsyncService;
    private final TextbookProperties properties;
    private final ConfigService configService;
    private final AuditService auditService;
    private final SemesterActiveService activeSemesterService;
    private final SemesterMapper semesterMapper;

    @Override
    public Long startImport(String bizType, Long semesterId, MultipartFile file) {
        String type = normalizeBizType(bizType);
        if ("change".equals(type)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "异动批量导入请使用 /api/secretary/change/import");
        }
        int maxFileMb = configService.getInt(ConfigService.IMPORT_MAX_FILE_MB,
                properties.getImportConfig().getMaxFileMb());
        ImportUploadValidator.validate(file, maxFileMb);
        Long targetSemester = resolveTargetSemester(type, semesterId);

        Path stored = saveUpload(file, type);
        ImportBatch batch = new ImportBatch();
        batch.setBizType(type);
        batch.setSemesterId(targetSemester);
        batch.setFileName(file.getOriginalFilename());
        batch.setFilePath(stored.toString());
        batch.setStatus("running");
        batch.setProgressPct(0);
        batch.setDeleted(0L);
        importBatchMapper.insert(batch);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("bizType", type);
        detail.put("semesterId", targetSemester);
        detail.put("fileName", file.getOriginalFilename());
        auditService.record(AuditService.IMPORT, "import_batch", String.valueOf(batch.getId()), detail);

        try {
            importAsyncService.process(type, batch.getId(), targetSemester, stored.toString(),
                    SecurityUtils.currentUser());
        } catch (Exception e) {
            // 线程池拒绝等：批次标记 failed，可重传
            log.error("导入任务提交失败: batchId={}, bizType={}", batch.getId(), type, e);
            markFailed(batch.getId(), e);
        }
        log.info("导入批次已启动: batchId={}, bizType={}, semesterId={}", batch.getId(), type, targetSemester);
        return batch.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public ImportBatch getBatch(Long batchId) {
        ImportBatch batch = importBatchMapper.selectByIdSoft(batchId);
        if (batch == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "导入批次不存在");
        }
        return batch;
    }

    @Override
    @Transactional(readOnly = true)
    public String errorFilePath(Long batchId) {
        ImportBatch batch = importBatchMapper.selectByIdSoft(batchId);
        return batch == null ? null : batch.getErrorFilePath();
    }

    // ============ 私有 ============

    private String normalizeBizType(String bizType) {
        String type = bizType == null ? "" : bizType.trim().toLowerCase();
        if (!SUPPORTED_BIZ_TYPES.contains(type)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不支持的导入类型: " + bizType);
        }
        return type;
    }

    /**
     * 目标学期：student/teacher/teacher_course 缺省取当前 active 学期（请求上下文优先，
     * SPEC §5.4 在途快照）；textbook 跨学期共用，可为空。
     */
    private Long resolveTargetSemester(String bizType, Long semesterId) {
        if ("textbook".equals(bizType)) {
            return semesterId;
        }
        Long target = semesterId != null ? semesterId : SemesterContextHolder.get();
        if (target == null) {
            target = activeSemesterService.activeId();
        }
        if (target == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "当前没有激活学期，请先创建并激活学期");
        }
        if (semesterMapper.selectByIdSoft(target) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "学期不存在");
        }
        return target;
    }

    private Path saveUpload(MultipartFile file, String bizType) {
        try {
            Path dir = Path.of(properties.getExport().getTmpDir(), "import");
            Files.createDirectories(dir);
            Path target = dir.resolve("import-" + bizType + "-" + System.currentTimeMillis() + ".xlsx");
            file.transferTo(target.toFile());
            return target;
        } catch (IOException e) {
            throw new BizException(ErrorCode.BIZ_ERROR, "文件保存失败，请重试");
        }
    }

    private void markFailed(Long batchId, Exception e) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("row", 0);
        entry.put("message", "导入失败：" + e.getMessage());
        ImportBatch update = new ImportBatch();
        update.setId(batchId);
        update.setStatus("failed");
        update.setProgressPct(100);
        update.setErrorDetail(List.of(entry));
        importBatchMapper.update(update, Wrappers.<ImportBatch>lambdaUpdate()
                .eq(ImportBatch::getId, batchId));
    }
}
