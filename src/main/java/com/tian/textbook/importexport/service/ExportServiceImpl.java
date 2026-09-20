package com.tian.textbook.importexport.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.CurrentUser;
import com.tian.textbook.common.SecurityUtils;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.importexport.ExportService;
import com.tian.textbook.importexport.dto.ExportPlan;
import com.tian.textbook.importexport.entity.ExportTask;
import com.tian.textbook.importexport.mapper.ExportTaskMapper;
import com.tian.textbook.notify.entity.NoticeTask;
import com.tian.textbook.notify.mapper.NoticeRecordMapper;
import com.tian.textbook.notify.mapper.NoticeTaskMapper;
import com.tian.textbook.order.mapper.OrderFormItemMapper;
import com.tian.textbook.order.mapper.StudentOrderItemMapper;
import com.tian.textbook.semester.SemesterActiveService;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.system.config.ConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 导出中心（SPEC §10 / Q16/W18）：同步流式（≤ 阈值）与异步任务（> 阈值）+ 一次性下载 token。
 *
 * <p>阈值判定：预估行数 > export.sync_row_threshold（system_config，默认 5000）走
 * export_task + 轮询；下载 token 单次有效（首次下载置空）、默认 10 分钟过期、文件保留 24 小时。
 * 每次导出（同步/异步创建）写审计（action=EXPORT）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportServiceImpl implements ExportService {

    private final ExportTaskMapper exportTaskMapper;
    private final ExportAsyncService exportAsyncService;
    private final ExportDataWriter dataWriter;
    private final OrderFormItemMapper orderFormItemMapper;
    private final StudentOrderItemMapper studentOrderItemMapper;
    private final NoticeRecordMapper noticeRecordMapper;
    private final NoticeTaskMapper noticeTaskMapper;
    private final UserSemesterProfileMapper profileMapper;
    private final SemesterActiveService activeSemesterService;
    private final ConfigService configService;
    private final AuditService auditService;

    @Override
    public boolean shouldGoAsync(int rowEstimate) {
        return rowEstimate > configService.getInt(ConfigService.EXPORT_SYNC_ROW_THRESHOLD, 5000);
    }

    @Override
    public ExportTask createAsyncTask(String bizType, Map<String, Object> params, int rowEstimate) {
        ExportTask task = new ExportTask();
        task.setBizType(bizType);
        task.setParamsJson(params);
        task.setRowEstimate(rowEstimate);
        task.setStatus("queued");
        task.setProgressPct(0);
        task.setDeleted(0L);
        CurrentUser user = SecurityUtils.currentUser();
        if (user != null) {
            task.setCreatedBy(user.userId());
        }
        exportTaskMapper.insert(task);
        try {
            exportAsyncService.generate(task.getId());
        } catch (Exception e) {
            log.error("导出任务提交失败: taskId={}, bizType={}", task.getId(), bizType, e);
            markFailed(task.getId(), e);
        }
        return exportTaskMapper.selectByIdSoft(task.getId());
    }

    @Override
    public ExportTask getTask(Long taskId) {
        ExportTask task = exportTaskMapper.selectByIdSoft(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "导出任务不存在");
        }
        return task;
    }

    @Override
    public void writeSync(String bizType, Map<String, Object> params, OutputStream out) {
        dataWriter.write(bizType, params, out);
    }

    @Override
    public ExportTask claimDownload(Long taskId, String token) {
        ExportTask task = getTask(taskId);
        LocalDateTime now = LocalDateTime.now();
        boolean valid = token != null && !token.isBlank()
                && token.equals(task.getDownloadToken())
                && "done".equals(task.getStatus())
                && task.getTokenExpireAt() != null && task.getTokenExpireAt().isAfter(now);
        if (!valid) {
            throw new BizException(ErrorCode.DOWNLOAD_TOKEN_INVALID);
        }
        // 单次有效：首次下载后置空
        exportTaskMapper.update(null, Wrappers.<ExportTask>lambdaUpdate()
                .eq(ExportTask::getId, taskId)
                .set(ExportTask::getDownloadToken, null));
        task.setDownloadToken(null);
        return task;
    }

    // ============ 端点编排（Controller 只解析 HTTP + 调本服务） ============

    /** 教师征订明细导出（秘书本院 / 超管全院） */
    public ExportPlan planOrderExport(Long semesterId, Long collegeId) {
        Map<String, Object> params = new LinkedHashMap<>();
        put(params, "semesterId", resolveSemester(semesterId));
        put(params, "collegeId", collegeId);
        return planExport("order", params, "教师征订明细.xlsx");
    }

    /** 秘书本院签字版导出：学院范围 = 当前用户 active 学期归属（W6） */
    public ExportPlan planSignatureExport(Long semesterId) {
        Long semester = resolveSemester(semesterId);
        CurrentUser user = SecurityUtils.currentUser();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        Long collegeId = profileMapper.selectCollegeId(user.userId(), semester);
        if (collegeId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "当前用户没有学院归属，无法生成签字版");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        put(params, "semesterId", semester);
        put(params, "collegeId", collegeId);
        return planExport("signature", params, "教材征订签字版.xlsx");
    }

    /** 学生选购汇总（参考用量，仅教材室） */
    public ExportPlan planStudentExport(Long semesterId) {
        Map<String, Object> params = new LinkedHashMap<>();
        put(params, "semesterId", resolveSemester(semesterId));
        return planExport("student", params, "学生选购汇总.xlsx");
    }

    /** 通知汇总（学期由通知任务归属推导） */
    public ExportPlan planNoticeExport(Long taskId) {
        NoticeTask task = noticeTaskMapper.selectByIdSoft(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "通知任务不存在");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        put(params, "taskId", taskId);
        put(params, "semesterId", task.getSemesterId());
        return planExport("notice", params, "通知汇总.xlsx");
    }

    /**
     * 进度查询（归属校验）：非 ADMIN 只能看自己创建的任务（created_by=本人），否则 403。
     */
    public ExportTask getTaskForUser(Long taskId) {
        ExportTask task = getTask(taskId);
        CurrentUser user = SecurityUtils.currentUser();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "登录已过期，请重新登录");
        }
        if (!user.isAdmin() && !user.userId().equals(task.getCreatedBy())) {
            throw new BizException(ErrorCode.RESOURCE_FORBIDDEN, "无权访问该导出任务");
        }
        return task;
    }

    /** 一次性下载（归属校验 + token 校验消费） */
    public ExportTask claimDownloadForUser(Long taskId, String token) {
        getTaskForUser(taskId);
        return claimDownload(taskId, token);
    }

    // ============ 私有 ============

    private ExportPlan planExport(String bizType, Map<String, Object> params, String fileName) {
        int rowEstimate = estimateRows(bizType, params);
        auditService.record(AuditService.EXPORT, "export", bizType, auditDetail(bizType, params, rowEstimate));
        if (shouldGoAsync(rowEstimate)) {
            ExportTask task = createAsyncTask(bizType, params, rowEstimate);
            return new ExportPlan(true, task.getId(), bizType, params, fileName, rowEstimate);
        }
        return new ExportPlan(false, null, bizType, params, fileName, rowEstimate);
    }

    /** 预估行数 = 数据源行数（阈值判定用，Q16） */
    private int estimateRows(String bizType, Map<String, Object> params) {
        return switch (bizType) {
            case "order", "signature" -> orderFormItemMapper
                    .selectReviewedItems(longParam(params, "semesterId"), longParam(params, "collegeId")).size();
            case "student" -> studentOrderItemMapper.selectSummaryRows(longParam(params, "semesterId")).size();
            case "notice" -> noticeRecordMapper.selectTaskSummaryRows(
                    longParam(params, "taskId"), longParam(params, "semesterId")).size();
            case "supplier" -> orderFormItemMapper.selectReviewedItems(longParam(params, "semesterId"), null).size();
            default -> 0;
        };
    }

    private Map<String, Object> auditDetail(String bizType, Map<String, Object> params, int rowEstimate) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("bizType", bizType);
        detail.put("rowEstimate", rowEstimate);
        if (params != null) {
            for (String key : new String[]{"semesterId", "collegeId", "taskId"}) {
                Object value = params.get(key);
                if (value != null) {
                    detail.put(key, value);
                }
            }
        }
        return detail;
    }

    private Long resolveSemester(Long requested) {
        Long semesterId = requested != null ? requested : SemesterContextHolder.get();
        if (semesterId == null) {
            semesterId = activeSemesterService.activeId();
        }
        if (semesterId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "当前没有激活学期，请先创建并激活学期");
        }
        return semesterId;
    }

    private void markFailed(Long taskId, Exception e) {
        ExportTask update = new ExportTask();
        update.setId(taskId);
        update.setStatus("failed");
        update.setErrorMsg(e.getMessage() == null ? "导出失败" :
                e.getMessage().length() > 255 ? e.getMessage().substring(0, 255) : e.getMessage());
        exportTaskMapper.update(update, Wrappers.<ExportTask>lambdaUpdate()
                .eq(ExportTask::getId, taskId));
    }

    private static void put(Map<String, Object> params, String key, Object value) {
        if (value != null) {
            params.put(key, value);
        }
    }

    private static Long longParam(Map<String, Object> params, String key) {
        if (params == null) {
            return null;
        }
        Object value = params.get(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            return Long.parseLong(s.trim());
        }
        return null;
    }
}
