package com.tian.textbook.approval.service;

import com.tian.textbook.approval.ChangeTypes;
import com.tian.textbook.approval.dto.ChangeImportRow;
import com.tian.textbook.approval.entity.ChangeRequest;
import com.tian.textbook.approval.mapper.ChangeRequestMapper;
import com.tian.textbook.common.CurrentUser;
import com.tian.textbook.common.FieldCheckIssue;
import com.tian.textbook.common.SecurityUtils;
import com.tian.textbook.importexport.support.ImportRunContext;
import com.tian.textbook.system.entity.College;
import com.tian.textbook.system.entity.SchoolClass;
import com.tian.textbook.system.entity.SysUser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 异动批量导入的行处理（BE-7b：接入统一异步导入链路）。
 *
 * <p>此前异动导入是**同步单事务**（整文件一个事务、无进度、无错误明细文件），
 * 5000 行时会长时间持有连接与行锁。现在走 {@code ImportAsyncService} 的 change 分支：
 * 每 500 行一个 {@code REQUIRES_NEW} 事务、{@code progress_pct} 进度、错误明细文件与
 * 500 条明细上限，与其余四类导入一致。</p>
 *
 * <p><b>规则不放松</b>：行校验复用 {@link ChangeRecordSupport}（与逐条提交同一套
 * 字段审查 + 范围校验），因此「非 ADMIN 只能对本院用户提交」在异步链路同样生效。</p>
 *
 * <p>行结果语义与旧同步导入一致：非空行**总会**落一条 change_request——字段审查通过
 * → {@code pending_review}，不通过 → {@code rejected} + field_check_result；不通过的行
 * 同时计入批次错误明细（供下载核对）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeImportHandler {

    private final ChangeRequestMapper changeRequestMapper;
    private final ChangeRecordSupport support;

    /**
     * 行校验（由 {@code ImportReadListener} 在解析线程调用）。
     *
     * @return 恒为 null：非空行一律落库（不通过的行以 rejected 落库，错误计数由 outcomeMessage 提供）
     */
    public String validate(ChangeImportRow row, int excelRow, ImportRunContext ctx) {
        CurrentUser current = SecurityUtils.requireCurrentUser();
        Long semesterId = ctx.semesterId();
        String type = support.normalizeImportType(row.getType());
        SysUser target = blank(row.getUserNo()) ? null : ctx.user(row.getUserNo().trim());
        ChangeRecordSupport.Belonging before = support.currentBelonging(
                target == null ? null : target.getId(), semesterId);

        Long collegeId = null;
        boolean collegeMissing = false;
        if (!blank(row.getCollegeName())) {
            College college = ctx.college(row.getCollegeName().trim());
            collegeId = college == null ? null : college.getId();
            collegeMissing = college == null;
        }

        // 班级按名称全局匹配（同名多条 = 严格模式行错误）；ctx 内按名称缓存
        Long classId = null;
        boolean classAmbiguous = false;
        if (!blank(row.getClassName())) {
            List<SchoolClass> matches = ctx.classesByName(row.getClassName().trim());
            if (matches.size() == 1) {
                classId = matches.get(0).getId();
            } else if (matches.size() > 1) {
                classAmbiguous = true;
            }
        }

        List<FieldCheckIssue> issues = support.fieldCheck(type, target, collegeId, classId,
                before.collegeId(), before.classId(), classAmbiguous);
        issues = support.withScopeCheck(issues, type, target, before, current, semesterId);
        if (ChangeRecordSupport.TYPE_TEACHER.equals(type) && !blank(row.getClassName())) {
            // 与逐条提交的 400 对齐：教师异动仅支持变更学院
            issues = new ArrayList<>(issues);
            issues.add(new FieldCheckIssue("targetClassId", "CLASS_EXISTS", "教师异动仅支持变更学院"));
        }
        if (collegeMissing && collegeId == null) {
            // ctx.college 命中不到时 fieldCheck 会以 COLLEGE_EXISTS 覆盖，这里只补日志便于排查
            log.debug("异动导入行学院未匹配: row={}, collegeName={}", excelRow, row.getCollegeName());
        }

        // 校验结果挂到行对象上（与 StudentImportRow 的 collegeId/classId 同模式），写入阶段直接使用
        row.setNormalizedType(type);
        row.setTargetUserId(target == null ? null : target.getId());
        row.setTargetCollegeId(collegeId);
        row.setTargetClassId(classId);
        row.setIssues(issues);
        // 关键：**非空行一律返回 null（= 交给 flusher 落库）**。字段审查不通过的行也要以
        // rejected 落库（申请人可见原因、审批端可按 batchNo 追溯），错误计数与明细由
        // RowOutcome（outcomeMessage）表达——只靠 validator 返回值无法表达「既不通过、又必须写入」。
        return null;
    }

    /** 落库（每 500 行一个独立事务，由 {@code ImportRowWriter} 同款 REQUIRES_NEW 承载）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(List<ChangeImportRow> rows, ImportRunContext ctx) {
        CurrentUser current = SecurityUtils.requireCurrentUser();
        String batchNo = ctx.batchNo();
        for (ChangeImportRow row : rows) {
            String type = row.getNormalizedType();
            ChangeRecordSupport.Belonging before = support.currentBelonging(row.getTargetUserId(),
                    ctx.semesterId());
            ChangeRequest record = support.buildChangeRequest(ctx.semesterId(), current.userId(), type,
                    row.getTargetUserId(), row.getTargetCollegeId(), row.getTargetClassId(),
                    before.collegeId(), before.classId(), row.getIssues(), batchNo, row.getReason(),
                    ChangeTypes.parse(row.getChangeType()));
            changeRequestMapper.insert(record);
        }
    }

    /**
     * 已落库行的业务结论（供 {@code ImportReadListener.RowOutcome}）：
     * 字段审查不通过的行已按 rejected 落库，但仍应计入批次错误明细。
     */
    public String outcomeMessage(ChangeImportRow row) {
        if (row.getIssues() == null || row.getIssues().isEmpty()) {
            return null;
        }
        return row.getIssues().get(0).message();
    }

    /** 逐批生成的异动批次号（change_request.batch_no）。 */
    public String newBatchNo() {
        return support.generateBatchNo();
    }

    /** 批次错误明细条目（row 从 2 开始：第 1 行是表头）。 */
    public Map<String, Object> errorDetail(ChangeImportRow row, int excelRow) {
        return support.rowErrorDetail(excelRow, row.getUserNo(), row.getIssues());
    }

    /** 行是否有错误（批次错误计数用）。 */
    public boolean hasIssues(ChangeImportRow row) {
        return row.getIssues() != null && !row.getIssues().isEmpty();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
