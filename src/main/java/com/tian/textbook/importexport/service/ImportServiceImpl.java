package com.tian.textbook.importexport.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.config.TextbookProperties;
import com.tian.textbook.common.CurrentUser;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.common.SecurityUtils;
import com.tian.textbook.common.util.FailureMessages;
import com.tian.textbook.importexport.ImportService;
import com.tian.textbook.importexport.dto.ImportPreviewResponse;
import com.tian.textbook.importexport.entity.ImportBatch;
import com.tian.textbook.importexport.mapper.ImportBatchMapper;
import com.tian.textbook.importexport.support.ImportUploadValidator;
import com.tian.textbook.semester.SemesterActiveService;
import com.tian.textbook.semester.mapper.SemesterMapper;
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
    private final ClassSizeGuard classSizeGuard;
    private final TextbookProperties properties;
    private final ConfigService configService;
    private final SemesterActiveService activeSemesterService;
    private final SemesterMapper semesterMapper;

    @Override
    public Long startImport(String bizType, Long semesterId, MultipartFile file,
                            boolean confirmClassSizeShrink) {
        String type = normalizeBizType(bizType);
        int maxFileMb = configService.getInt(ConfigService.IMPORT_MAX_FILE_MB,
                properties.getImportConfig().getMaxFileMb());
        ImportUploadValidator.validate(file, maxFileMb);
        Long targetSemester = resolveTargetSemester(type, semesterId);

        CurrentUser operator = SecurityUtils.currentUser();
        Path stored = saveUpload(file, type);
        // 局部名单门禁（B13）：学生名单会按文件内人数重算班级人数（= 教师填报数量上限），
        // 疑似局部名单（下调幅度超阈值）时必须显式确认，否则 409 且不建批次（避免"已受理再失败"
        // 的半成品状态）。门禁与预览共用同一套扫描逻辑，判定口径天然一致。
        //
        // 成本（真库实测，本机 MySQL）：扫描 ≈1.3ms/行——10k 行文件给导入请求增加约 13s，
        // 而同一批次的异步导入耗时约 5 分钟（BCrypt + 分批评分），即门禁只占约 4%；
        // 已确认（confirmClassSizeShrink=true，前端预览确认后的正常路径）时**完全跳过扫描**
        // （实测请求 311ms），因此只有「未预览就直接导入」的调用方才付出扫描成本。
        // 文件大小已由 import.max_file_mb（默认 10MB）兜底，扫描耗时随行数线性增长。
        if ("student".equals(type) && !confirmClassSizeShrink) {
            try {
                ImportPreviewResponse preview = scanStudent(type, targetSemester, stored, false);
                if (preview.requiresConfirm()) {
                    deleteQuietly(stored);
                    throw new BizException(ErrorCode.STATE_CONFLICT, classSizeGuard.confirmMessage(
                            preview.classSizeDiffs()));
                }
            } catch (BizException e) {
                deleteQuietly(stored);
                throw e;
            }
        }
        ImportBatch batch = new ImportBatch();
        batch.setBizType(type);
        batch.setSemesterId(targetSemester);
        batch.setFileName(file.getOriginalFilename());
        batch.setFilePath(stored.toString());
        batch.setStatus("running");
        batch.setProgressPct(0);
        batch.setDeleted(0L);
        // created_by 必须落库：批次查询端点此前只有 import:batch:view 权限码、没有归属校验，
        // 而 SECRETARY 也持有该权限码，于是任一秘书可枚举 batchId 读取任意批次的上传文件名与
        // 错误明细并下载错误明细 xlsx。归属字段是后续 getBatchForUser 校验的前提。
        if (operator != null) {
            batch.setCreatedBy(operator.userId());
            batch.setUpdatedBy(operator.userId());
        }
        importBatchMapper.insert(batch);

        // 导入启动审计由 @AuditLog 切面记录（controller 层）；完成摘要（含停用计数）由 ImportAsyncService 落库

        try {
            importAsyncService.process(type, batch.getId(), targetSemester, stored.toString(),
                    SecurityUtils.currentUser(), confirmClassSizeShrink);
        } catch (Exception e) {
            // 线程池拒绝等：批次标记 failed，可重传
            log.error("导入任务提交失败: batchId={}, bizType={}", batch.getId(), type, e);
            markFailed(batch.getId(), e);
        }
        log.info("导入批次已启动: batchId={}, bizType={}, semesterId={}, 班级人数下调已确认={}",
                batch.getId(), type, targetSemester, confirmClassSizeShrink);
        return batch.getId();
    }

    /**
     * 导入预览（只读）：管理员在提交前核对「班级人数 diff / 将新建账号数 / 将停用账号数」。
     *
     * <p>不落盘、不建批次、不写任何表；校验口径与真实导入完全一致（同一套 validator）。</p>
     */
    @Override
    public ImportPreviewResponse previewImport(String bizType, Long semesterId, MultipartFile file) {
        String type = normalizeBizType(bizType);
        if (!"student".equals(type) && !"teacher".equals(type)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "导入预览仅支持学生/教师名单（bizType=" + type + "）");
        }
        int maxFileMb = configService.getInt(ConfigService.IMPORT_MAX_FILE_MB,
                properties.getImportConfig().getMaxFileMb());
        ImportUploadValidator.validate(file, maxFileMb);
        Long targetSemester = resolveTargetSemester(type, semesterId);
        try (java.io.InputStream in = file.getInputStream()) {
            return importAsyncService.preview(type, targetSemester, in, true);
        } catch (IOException e) {
            throw new BizException(ErrorCode.BIZ_ERROR, "文件读取失败，请重试");
        }
    }

    /** 只读扫描落盘文件（门禁与预览共用入口）。 */
    private ImportPreviewResponse scanStudent(String type, Long targetSemester, Path stored,
                                              boolean runDisableComparison) {
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(stored)) {
            return importAsyncService.preview(type, targetSemester, in, runDisableComparison);
        } catch (IOException e) {
            throw new BizException(ErrorCode.BIZ_ERROR, "文件读取失败，请重试");
        }
    }

    private void deleteQuietly(Path path) {
        try {
            java.nio.file.Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("导入临时文件删除失败: {}", path);
        }
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

    /**
     * 批次进度（归属校验）：非 ADMIN 只能读自己发起的批次，否则 404。
     *
     * <p>不通过 403/404 差异泄露批次是否存在（错误明细含上传文件名与逐行错误，
     * 跨账号可读即等于把他人导入内容暴露出去）。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public ImportBatch getBatchForUser(Long batchId) {
        ImportBatch batch = getBatch(batchId);
        CurrentUser user = SecurityUtils.currentUser();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        if (!user.isAdmin() && !user.userId().equals(batch.getCreatedBy())) {
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

    /**
     * 错误明细文件路径（文件已不存在时抛 404 并说明「已过期清理」）。
     *
     * <p>导出/导入临时文件保留 24 小时，之后由定时任务删除。此前的 404 文案是
     * 「该批次没有错误明细」——同一句话覆盖「本来就没错误」与「错误明细已过期」两种情况，
     * 前端与用户无法区分，只能反复重试。此处按「批次确实有错误明细但文件已不在」给出明确提示。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public String errorFilePathForDownload(Long batchId) {
        ImportBatch batch = getBatchForUser(batchId);
        String filePath = batch.getErrorFilePath();
        if (filePath == null || filePath.isBlank()) {
            throw new BizException(ErrorCode.NOT_FOUND, "该批次没有错误明细");
        }
        if (!java.nio.file.Files.isReadable(java.nio.file.Path.of(filePath))) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "错误明细文件已过期清理（保留 " + properties.getExport().getRetentionHours() + " 小时），请重新导入");
        }
        return filePath;
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
            // 必须绝对化：Part.write 对相对路径会以 multipart location 为基准，未配置 location 时抛异常
            // （默认 textbook.export.tmp-dir=./data/export 是相对路径，会导致全部导入接口 500/BIZ_ERROR）
            Path dir = Path.of(properties.getExport().getTmpDir(), "import").toAbsolutePath();
            Files.createDirectories(dir);
            Path target = dir.resolve("import-" + bizType + "-" + System.currentTimeMillis() + ".xlsx");
            file.transferTo(target);
            return target;
        } catch (IOException e) {
            throw new BizException(ErrorCode.BIZ_ERROR, "文件保存失败，请重试");
        }
    }

    private void markFailed(Long batchId, Exception e) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("row", 0);
        entry.put("message", "导入失败：" + FailureMessages.userFacing(e, "服务异常，请重试或联系教材室"));
        ImportBatch update = new ImportBatch();
        update.setId(batchId);
        update.setStatus("failed");
        update.setProgressPct(100);
        update.setErrorDetail(List.of(entry));
        importBatchMapper.update(update, Wrappers.<ImportBatch>lambdaUpdate()
                .eq(ImportBatch::getId, batchId));
    }
}
