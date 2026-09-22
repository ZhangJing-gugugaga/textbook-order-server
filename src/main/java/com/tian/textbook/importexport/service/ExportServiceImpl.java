package com.tian.textbook.importexport.service;

import com.tian.textbook.common.util.AppTime;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.CurrentUser;
import com.tian.textbook.common.SecurityUtils;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.common.util.FailureMessages;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /** 供货商导出 bizType（供货商侧任务读取/下载的归属白名单） */
    public static final String SUPPLIER_BIZ_TYPE = "supplier";

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
        // 派发必须在事务提交后：调用方（如 SupplierService.planExport）本身在 @Transactional 内，
        // 若在事务内直接投递 @Async，异步线程会在提交前查不到该行并直接 return，
        // 任务永远停在 queued（无补偿、无终态）。无事务上下文时立即派发。
        Long taskId = task.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatchAsync(taskId, bizType);
                }
            });
        } else {
            dispatchAsync(taskId, bizType);
        }
        return exportTaskMapper.selectByIdSoft(taskId);
    }

    private void dispatchAsync(Long taskId, String bizType) {
        try {
            exportAsyncService.generate(taskId);
        } catch (Exception e) {
            log.error("导出任务提交失败: taskId={}, bizType={}", taskId, bizType, e);
            markFailed(taskId, e);
        }
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
        LocalDateTime now = AppTime.now();
        boolean valid = token != null && !token.isBlank()
                && token.equals(task.getDownloadToken())
                && "done".equals(task.getStatus())
                && task.getTokenExpireAt() != null && task.getTokenExpireAt().isAfter(now)
                // 文件保留期（export.retention-hours，默认 24 小时）同样约束下载：
                // 此前只看 token 过期时间，token 有效期内可下载到已超过保留策略的文件
                && (task.getExpiresAt() == null || task.getExpiresAt().isAfter(now));
        if (!valid) {
            throw new BizException(ErrorCode.DOWNLOAD_TOKEN_INVALID);
        }
        // 单次有效：以 download_token 作为 CAS 谓词原子消费——并发同 token 请求只有一个能成功。
        // 仅按 id 更新时每个并发请求都能通过上面的校验，「单次有效」形同虚设。
        int rows = exportTaskMapper.update(null, Wrappers.<ExportTask>lambdaUpdate()
                .eq(ExportTask::getId, taskId)
                .eq(ExportTask::getDownloadToken, token)
                .set(ExportTask::getDownloadToken, null));
        if (rows == 0) {
            throw new BizException(ErrorCode.DOWNLOAD_TOKEN_INVALID);
        }
        task.setDownloadToken(null);
        return task;
    }

    // ============ 端点编排（Controller 只解析 HTTP + 调本服务） ============

    /**
     * 教师征订明细导出（秘书本院 / 超管全院）。
     *
     * <p>collegeId 虽由调用方传入，但非 ADMIN 身份一律忽略入参、强制由
     * user_semester_profile 的归属推导——否则秘书传任意 collegeId（或干脆不传，
     * 使 SQL 的 {@code <if test="collegeId != null">} 整段失效）即可导出他院乃至全院明细。</p>
     */
    public ExportPlan planOrderExport(Long semesterId, Long collegeId) {
        Long semester = resolveSemester(semesterId);
        CurrentUser user = SecurityUtils.requireCurrentUser();
        Long effectiveCollegeId = user.isAdmin() ? collegeId : requireOwnCollegeId(user, semester);
        Map<String, Object> params = new LinkedHashMap<>();
        put(params, "semesterId", semester);
        put(params, "collegeId", effectiveCollegeId);
        return planExport("order", params, "教师征订明细.xlsx");
    }

    /** 秘书本院签字版导出：学院范围 = 当前用户 active 学期归属（W6） */
    public ExportPlan planSignatureExport(Long semesterId) {
        Long semester = resolveSemester(semesterId);
        CurrentUser user = SecurityUtils.requireCurrentUser();
        Map<String, Object> params = new LinkedHashMap<>();
        put(params, "semesterId", semester);
        put(params, "collegeId", requireOwnCollegeId(user, semester));
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
        CurrentUser user = SecurityUtils.requireCurrentUser();
        if (!user.isAdmin() && !user.userId().equals(task.getCreatedBy())) {
            // 统一 404：403 与 404 的差异可被用来探测「该任务 id 是否存在」
            throw new BizException(ErrorCode.NOT_FOUND, "导出任务不存在");
        }
        return task;
    }

    /**
     * 供货商侧任务读取（归属校验 + bizType 白名单）。
     *
     * <p>供货商导出任务与内部导出任务共用 export_task 表，主键自增。若只按 id 取任务，
     * 任一供货商账号即可枚举 id 读到内部任务（教师征订/学生选购/通知汇总）的元数据；
     * 叠加 token 下发即等于越权下载任意导出文件。故此处双重约束：
     * 任务必须由本人创建，且 bizType 必须是 supplier。</p>
     */
    public ExportTask getSupplierTask(Long taskId) {
        ExportTask task = getTask(taskId);
        CurrentUser user = SecurityUtils.requireCurrentUser();
        if (!SUPPLIER_BIZ_TYPE.equals(task.getBizType())
                || (!user.isAdmin() && !user.userId().equals(task.getCreatedBy()))) {
            // 两类失败都返回 404（而非 403）：403/404 的差异本身就是「该 id 存在」的信息泄露，
            // 可用于枚举探测内部任务
            throw new BizException(ErrorCode.NOT_FOUND, "导出任务不存在");
        }
        return task;
    }

    /** 供货商一次性下载（归属校验 + bizType 白名单 + token 校验消费） */
    public ExportTask claimSupplierDownload(Long taskId, String token) {
        getSupplierTask(taskId);
        return claimDownload(taskId, token);
    }

    /** 一次性下载（归属校验 + token 校验消费） */
    public ExportTask claimDownloadForUser(Long taskId, String token) {
        getTaskForUser(taskId);
        return claimDownload(taskId, token);
    }

    // ============ 私有 ============

    /** 非超管的学院范围：只认 user_semester_profile 归属，缺失即拒绝（绝不退化为全院）。 */
    private Long requireOwnCollegeId(CurrentUser user, Long semesterId) {
        Long collegeId = profileMapper.selectCollegeId(user.userId(), semesterId);
        if (collegeId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "当前用户在该学期没有学院归属，无法导出本院数据");
        }
        return collegeId;
    }

    private ExportPlan planExport(String bizType, Map<String, Object> params, String fileName) {
        int rowEstimate = estimateRows(bizType, params);
        auditService.record(AuditService.EXPORT, "export", bizType, auditDetail(bizType, params, rowEstimate));
        if (shouldGoAsync(rowEstimate)) {
            ExportTask task = createAsyncTask(bizType, params, rowEstimate);
            return new ExportPlan(true, task.getId(), bizType, params, fileName, rowEstimate);
        }
        return new ExportPlan(false, null, bizType, params, fileName, rowEstimate);
    }

    /**
     * 预估行数 = 数据源行数（阈值判定用，Q16）。
     *
     * <p>一律走 COUNT 查询：此前四类导出都调 {@code .size()} 把整表结果物化进内存，
     * 仅为与阈值比较一次，数据量上去后单次导出即内存尖峰。</p>
     */
    private int estimateRows(String bizType, Map<String, Object> params) {
        long rows = switch (bizType) {
            case "order", "signature" -> orderFormItemMapper.countReviewedItems(
                    longParam(params, "semesterId"), longParam(params, "collegeId"));
            case "student" -> studentOrderItemMapper.countSummaryRows(longParam(params, "semesterId"));
            case "notice" -> noticeRecordMapper.countTaskSummaryRows(
                    longParam(params, "taskId"), longParam(params, "semesterId"));
            case "supplier" -> orderFormItemMapper.countReviewedItems(longParam(params, "semesterId"), null);
            default -> 0L;
        };
        return (int) Math.min(rows, Integer.MAX_VALUE);
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
        // 只透传业务异常文案：原始异常消息可能含 SQL/表名/服务器绝对路径
        update.setErrorMsg(FailureMessages.userFacing(e, "导出任务提交失败，请重试"));
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
