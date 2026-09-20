package com.tian.textbook.approval.service;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tian.textbook.approval.dto.ChangeBatchReviewRequest;
import com.tian.textbook.approval.dto.ChangeBatchReviewResult;
import com.tian.textbook.approval.dto.ChangeImportResult;
import com.tian.textbook.approval.dto.ChangeImportRow;
import com.tian.textbook.approval.dto.ChangeRequestListItem;
import com.tian.textbook.approval.dto.ChangeRequestVO;
import com.tian.textbook.approval.dto.ChangeReviewRequest;
import com.tian.textbook.approval.dto.ChangeSubmitRequest;
import com.tian.textbook.approval.entity.ChangeRequest;
import com.tian.textbook.approval.mapper.ChangeRequestMapper;
import com.tian.textbook.common.CurrentUser;
import com.tian.textbook.common.FieldCheckIssue;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.common.SecurityUtils;
import com.tian.textbook.common.config.TextbookProperties;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.importexport.entity.ImportBatch;
import com.tian.textbook.importexport.mapper.ImportBatchMapper;
import com.tian.textbook.semester.SemesterActiveService;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.entity.UserSemesterProfile;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.system.entity.College;
import com.tian.textbook.system.entity.SchoolClass;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.mapper.CollegeMapper;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 异动两级审批（PRD 模块 6 / SPEC §8 / 03 §6.3，Q10 批量、W15 立即生效、W16 教师仅学院）。
 *
 * <p>状态机：pending_field_check → pending_review → approved（写 user_semester_profile
 * active 学期归属）/ rejected。</p>
 *
 * <ul>
 *   <li>字段审查（TARGET_EXISTS / COLLEGE_EXISTS / CLASS_EXISTS(仅 student) / TYPE_VALID /
 *       VALUE_CHANGED）失败不抛 400，落库 rejected + field_check_result + reason=首条错误，
 *       供申请人查看（异动无 rejected_auto 状态）；</li>
 *   <li>审批通过与写 profile、审计同事务（SPEC §12）；教师异动只改 college_id（W16）；</li>
 *   <li>批量导入同步解析（EasyExcel），逐行审查、共享 batch_no，落 import_batch(biz_type=change)。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChangeRequestService {

    private static final String TYPE_STUDENT = "student";
    private static final String TYPE_TEACHER = "teacher";

    private static final String STATUS_PENDING_REVIEW = "pending_review";
    private static final String STATUS_APPROVED = "approved";
    private static final String STATUS_REJECTED = "rejected";

    private static final String ACTION_PASS = "pass";
    private static final String ACTION_REJECT = "reject";

    private static final DateTimeFormatter BATCH_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 目标学号/工号无法解析时的占位 target_user_id（DDL NOT NULL，无外键；记录为 rejected 终态，不进审批） */
    private static final long UNSET_TARGET_USER_ID = 0L;

    /** 导入行错误明细上限（避免 error_detail JSON 过大） */
    private static final int MAX_IMPORT_ERROR_DETAIL = 500;

    private final ChangeRequestMapper changeRequestMapper;
    private final SysUserMapper userMapper;
    private final CollegeMapper collegeMapper;
    private final SchoolClassMapper classMapper;
    private final UserSemesterProfileMapper profileMapper;
    private final ImportBatchMapper importBatchMapper;
    private final SemesterActiveService activeSemesterService;
    private final AuditService auditService;
    private final TextbookProperties properties;
    private final ObjectMapper objectMapper;

    // ============ 逐条提交（教师/秘书） ============

    /**
     * 逐条提交异动：字段审查通过 → pending_review；失败 → rejected + field_check_result（均落库）。
     *
     * @return 落库后的记录（含字段审查逐项错误）
     */
    @Transactional
    public ChangeRequestVO submit(ChangeSubmitRequest request) {
        CurrentUser current = SecurityUtils.requireCurrentUser();
        Semester active = requireActiveSemester();
        String type = trimToEmpty(request.type());
        if (TYPE_TEACHER.equals(type) && request.targetClassId() != null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "教师异动仅支持变更学院");
        }
        String targetUserNo = trimToEmpty(request.targetUserNo());
        SysUser target = targetUserNo.isEmpty() ? null : userMapper.selectByUserNo(targetUserNo);
        Belonging before = currentBelonging(target == null ? null : target.getId(), active.getId());
        List<FieldCheckIssue> issues = fieldCheck(type, target, request.targetCollegeId(),
                request.targetClassId(), before.collegeId(), before.classId(), false);

        ChangeRequest changeRequest = buildChangeRequest(active.getId(), current.userId(), type,
                target == null ? null : target.getId(), request.targetCollegeId(), request.targetClassId(),
                before.collegeId(), before.classId(), issues, null, null);
        changeRequestMapper.insert(changeRequest);
        if (issues.isEmpty()) {
            log.info("异动提交: applicant={}, targetUserNo={}, type={}, id={}",
                    current.userNo(), targetUserNo, type, changeRequest.getId());
        } else {
            log.info("异动字段审查未通过: applicant={}, targetUserNo={}, issues={}",
                    current.userNo(), targetUserNo, issues.size());
        }
        return enrich(List.of(changeRequest)).get(0);
    }

    // ============ 批量导入（秘书，同步解析） ============

    /**
     * 批量导入异动（EasyExcel 同步解析，逐行字段审查，共享 batch_no）：
     * 落 import_batch(biz_type=change, status=done) + 逐行 change_request。
     */
    @Transactional
    public ChangeImportResult importRows(MultipartFile file) {
        CurrentUser current = SecurityUtils.requireCurrentUser();
        Semester active = requireActiveSemester();
        validateFile(file);
        List<ChangeImportRow> rows = readRows(file);

        String batchNo = generateBatchNo();
        ImportBatch batch = new ImportBatch();
        batch.setBizType("change");
        batch.setSemesterId(active.getId());
        batch.setFileName(file.getOriginalFilename());
        batch.setBatchNo(batchNo);
        batch.setStatus("running");
        batch.setTotal(0);
        batch.setOkCount(0);
        batch.setErrorCount(0);
        batch.setProgressPct(0);
        importBatchMapper.insert(batch);

        int ok = 0;
        int error = 0;
        List<Map<String, Object>> errorDetail = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ChangeImportRow row = rows.get(i);
            if (isBlankRow(row)) {
                continue;
            }
            int rowNo = i + 2; // 第 1 行为表头
            String type = normalizeImportType(row.getType());
            SysUser target = blank(row.getUserNo()) ? null : userMapper.selectByUserNo(row.getUserNo().trim());
            Belonging before = currentBelonging(target == null ? null : target.getId(), active.getId());

            Long collegeId = null;
            if (!blank(row.getCollegeName())) {
                College college = collegeMapper.selectByName(row.getCollegeName().trim());
                collegeId = college == null ? null : college.getId();
            }
            boolean classAmbiguous = false;
            Long classId = null;
            if (!blank(row.getClassName())) {
                List<SchoolClass> matches = classMapper.selectList(Wrappers.<SchoolClass>lambdaQuery()
                        .eq(SchoolClass::getName, row.getClassName().trim())
                        .eq(SchoolClass::getDeleted, 0));
                if (matches.size() == 1) {
                    classId = matches.get(0).getId();
                } else if (matches.size() > 1) {
                    classAmbiguous = true; // 同名班级跨专业/学院存在，严格模式视为行错误
                }
            }

            List<FieldCheckIssue> issues = fieldCheck(type, target, collegeId, classId,
                    before.collegeId(), before.classId(), classAmbiguous);
            if (TYPE_TEACHER.equals(type) && !blank(row.getClassName())) {
                // 与逐条提交的 400 对齐：教师异动仅支持变更学院
                issues = new ArrayList<>(issues);
                issues.add(new FieldCheckIssue("targetClassId", "CLASS_EXISTS", "教师异动仅支持变更学院"));
            }

            ChangeRequest changeRequest = buildChangeRequest(active.getId(), current.userId(), type,
                    target == null ? null : target.getId(), collegeId, classId,
                    before.collegeId(), before.classId(), issues, batchNo, row.getReason());
            changeRequestMapper.insert(changeRequest);
            if (issues.isEmpty()) {
                ok++;
            } else {
                error++;
                if (errorDetail.size() < MAX_IMPORT_ERROR_DETAIL) {
                    errorDetail.add(rowErrorDetail(rowNo, row.getUserNo(), issues));
                }
            }
        }

        int total = ok + error;
        batch.setTotal(total);
        batch.setOkCount(ok);
        batch.setErrorCount(error);
        batch.setProgressPct(100);
        batch.setStatus("done");
        if (!errorDetail.isEmpty()) {
            batch.setErrorDetail(errorDetail);
        }
        importBatchMapper.updateById(batch);
        log.info("异动批量导入完成: batchNo={}, batchId={}, total={}, ok={}, error={}, applicant={}",
                batchNo, batch.getId(), total, ok, error, current.userNo());

        ChangeImportResult result = new ChangeImportResult();
        result.setBatchId(batch.getId());
        result.setBatchNo(batchNo);
        result.setTotal(total);
        result.setOkCount(ok);
        result.setErrorCount(error);
        return result;
    }

    // ============ 我的提交记录 ============

    /** 我的提交记录（applicant_id = 本人，@CollegeScope 数据隔离），回填目标用户/学院/班级名称 */
    @Transactional(readOnly = true)
    public List<ChangeRequestVO> myRequests() {
        CurrentUser current = SecurityUtils.requireCurrentUser();
        List<ChangeRequest> records = changeRequestMapper.selectMyRequests(current.userId());
        attachPayloads(records);
        return enrich(records);
    }

    // ============ 审批列表（超管） ============

    /** 审批列表分页（semesterId/status/batchNo/type 过滤） */
    @Transactional(readOnly = true)
    public PageResponse<ChangeRequestListItem> page(Long semesterId, String status, String batchNo, String type,
                                                    long page, long size) {
        long safeSize = Math.min(Math.max(size, 1), 200);
        long safePage = Math.max(page, 1);
        long offset = (safePage - 1) * safeSize;
        List<ChangeRequestListItem> list = changeRequestMapper.selectPageByFilter(
                semesterId, trimToEmpty(status), trimToEmpty(batchNo), trimToEmpty(type), offset, safeSize);
        long total = changeRequestMapper.countByFilter(
                semesterId, trimToEmpty(status), trimToEmpty(batchNo), trimToEmpty(type));
        return PageResponse.of(list, safePage, safeSize, total);
    }

    // ============ 单条审批 ============

    /**
     * 单条审批：仅 pending_review 可审（否则 409）；pass 同事务写 user_semester_profile（W15 立即生效）
     * + 审计；reject 理由必填。
     */
    @Transactional
    public ChangeRequestVO review(Long id, ChangeReviewRequest request) {
        CurrentUser current = SecurityUtils.requireCurrentUser();
        String action = normalizeAction(request.action());
        String reason = validateReason(action, request.reason());
        ChangeRequest changeRequest = changeRequestMapper.selectByIdSoft(id);
        if (changeRequest == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "异动记录不存在");
        }
        if (!STATUS_PENDING_REVIEW.equals(changeRequest.getStatus())) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "该异动已处理，请刷新后重试");
        }
        attachPayloads(List.of(changeRequest));
        applyReview(changeRequest, action, reason, current);
        log.info("异动审批: id={}, action={}, reviewer={}", id, action, current.userNo());
        return enrich(List.of(changeRequest)).get(0);
    }

    // ============ 按批次批量审批 ============

    /**
     * 按批次批量审批：该 batchNo 下全部 pending_review 记录逐条走单条审批逻辑（同一事务）；
     * 已处理过的记录跳过（幂等），批次不存在 → 404。
     */
    @Transactional
    public ChangeBatchReviewResult batchReview(ChangeBatchReviewRequest request) {
        CurrentUser current = SecurityUtils.requireCurrentUser();
        String action = normalizeAction(request.action());
        String reason = validateReason(action, request.reason());
        String batchNo = request.batchNo().trim();
        List<ChangeRequest> all = changeRequestMapper.selectByBatchNo(batchNo);
        if (all.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "批次不存在");
        }
        List<ChangeRequest> pending = all.stream()
                .filter(r -> STATUS_PENDING_REVIEW.equals(r.getStatus()))
                .toList();
        attachPayloads(pending);
        for (ChangeRequest changeRequest : pending) {
            applyReview(changeRequest, action, reason, current);
        }
        log.info("异动批量审批: batchNo={}, action={}, processed={}, reviewer={}",
                batchNo, action, pending.size(), current.userNo());
        ChangeBatchReviewResult result = new ChangeBatchReviewResult();
        result.setBatchNo(batchNo);
        result.setAction(action);
        result.setCount(pending.size());
        result.setRecords(enrich(pending));
        return result;
    }

    // ============ 审批落库（单条/批量共用，同一事务） ============

    /**
     * 审批一条：pass → 写 active 学期 user_semester_profile（student 改 college_id+class_id，
     * teacher 只改 college_id，W16）+ 审计；reject → reason + 审计。
     *
     * <p>入参实体须已 {@link #attachPayloads} 回填 payload（审批通过读 after 值生效落库）。</p>
     */
    private void applyReview(ChangeRequest changeRequest, String action, String reason, CurrentUser current) {
        Long id = changeRequest.getId();
        if (ACTION_PASS.equals(action)) {
            applyToProfile(changeRequest);
            changeRequest.setStatus(STATUS_APPROVED);
        } else {
            changeRequest.setStatus(STATUS_REJECTED);
            changeRequest.setReason(reason);
        }
        changeRequest.setReviewerId(current.userId());
        changeRequest.setReviewAt(LocalDateTime.now());
        // 局部实体更新（MP 非空字段策略）：不动 payload/field_check_result，updated_at 交 DB 自动刷新
        ChangeRequest update = new ChangeRequest();
        update.setId(id);
        update.setStatus(changeRequest.getStatus());
        if (!ACTION_PASS.equals(action)) {
            update.setReason(reason);
        }
        update.setReviewerId(current.userId());
        update.setReviewAt(changeRequest.getReviewAt());
        changeRequestMapper.update(update, Wrappers.<ChangeRequest>lambdaUpdate()
                .eq(ChangeRequest::getId, id));
        // 审批通过落库与审计同事务（SPEC §12）
        auditService.record(AuditService.CHANGE, "change", String.valueOf(id),
                Map.of("action", action, "targetUserId", String.valueOf(changeRequest.getTargetUserId())));
    }

    /**
     * 生效落库（W15）：upsert active 学期 user_semester_profile。
     * teacher 仅更新 college_id（class_id 保持原值，MP 非空字段更新策略天然跳过 null）。
     */
    private void applyToProfile(ChangeRequest changeRequest) {
        Semester active = requireActiveSemester();
        Belonging after = readBelonging(changeRequest.getPayloadJson(), "after");
        UserSemesterProfile existing =
                profileMapper.selectByUserAndSemester(changeRequest.getTargetUserId(), active.getId());
        if (existing == null) {
            UserSemesterProfile profile = new UserSemesterProfile();
            profile.setUserId(changeRequest.getTargetUserId());
            profile.setSemesterId(active.getId());
            profile.setCollegeId(after.collegeId());
            profile.setClassId(TYPE_STUDENT.equals(changeRequest.getType()) ? after.classId() : null);
            profile.setStatus(1);
            profile.setDeleted(0L);
            profileMapper.insert(profile);
        } else {
            UserSemesterProfile update = new UserSemesterProfile();
            update.setId(existing.getId());
            update.setCollegeId(after.collegeId());
            if (TYPE_STUDENT.equals(changeRequest.getType())) {
                update.setClassId(after.classId());
            }
            profileMapper.update(update, Wrappers.<UserSemesterProfile>lambdaUpdate()
                    .eq(UserSemesterProfile::getId, existing.getId()));
        }
    }

    // ============ 字段审查（SPEC §8 异动规则集） ============

    /**
     * 异动字段审查（逐行执行，错误落 field_check_result）：
     * TARGET_EXISTS / COLLEGE_EXISTS / CLASS_EXISTS（仅 student）/ TYPE_VALID / VALUE_CHANGED。
     *
     * @param classAmbiguous 目标班级同名多条（严格模式：导入行按 CLASS_EXISTS 行错误处理）
     */
    private List<FieldCheckIssue> fieldCheck(String type, SysUser target, Long targetCollegeId, Long targetClassId,
                                             Long beforeCollegeId, Long beforeClassId, boolean classAmbiguous) {
        List<FieldCheckIssue> issues = new ArrayList<>();
        if (!TYPE_STUDENT.equals(type) && !TYPE_TEACHER.equals(type)) {
            issues.add(new FieldCheckIssue("type", "TYPE_VALID", "变更类型不合法"));
        }
        if (target == null) {
            issues.add(new FieldCheckIssue("targetUserNo", "TARGET_EXISTS",
                    TYPE_TEACHER.equals(type) ? "目标工号不存在" : "目标学号不存在"));
        }
        if (targetCollegeId == null || collegeMapper.selectByIdSoft(targetCollegeId) == null) {
            issues.add(new FieldCheckIssue("targetCollegeId", "COLLEGE_EXISTS", "目标学院不存在"));
        }
        if (TYPE_STUDENT.equals(type)) {
            if (classAmbiguous) {
                issues.add(new FieldCheckIssue("targetClassId", "CLASS_EXISTS", "目标班级不唯一，请联系教材室核实"));
            } else if (targetClassId == null || classMapper.selectByIdSoft(targetClassId) == null) {
                issues.add(new FieldCheckIssue("targetClassId", "CLASS_EXISTS", "目标班级不存在"));
            }
        }
        Long afterClassId = TYPE_TEACHER.equals(type) ? beforeClassId : targetClassId;
        if (Objects.equals(beforeCollegeId, targetCollegeId) && Objects.equals(beforeClassId, afterClassId)) {
            issues.add(new FieldCheckIssue("payload", "VALUE_CHANGED", "变更内容无变化"));
        }
        return issues;
    }

    // ============ 记录构建 ============

    /**
     * 构建 change_request：通过 → pending_review；失败 → rejected + field_check_result
     * + reason=首条错误信息。导入通过行的 reason 取行内「原因」。
     */
    private ChangeRequest buildChangeRequest(Long semesterId, Long applicantId, String type, Long targetUserId,
                                             Long targetCollegeId, Long targetClassId,
                                             Long beforeCollegeId, Long beforeClassId,
                                             List<FieldCheckIssue> issues, String batchNo, String rowReason) {
        ChangeRequest changeRequest = new ChangeRequest();
        changeRequest.setSemesterId(semesterId);
        changeRequest.setType(type);
        changeRequest.setTargetUserId(targetUserId == null ? UNSET_TARGET_USER_ID : targetUserId);
        changeRequest.setBatchNo(batchNo);
        changeRequest.setApplicantId(applicantId);
        // teacher 可变更字段仅 college_id（W16）：after.classId 保持原值
        Long afterClassId = TYPE_TEACHER.equals(type) ? beforeClassId : targetClassId;
        changeRequest.setPayloadJson(payload(beforeCollegeId, beforeClassId, targetCollegeId, afterClassId));
        if (issues.isEmpty()) {
            changeRequest.setStatus(STATUS_PENDING_REVIEW);
            if (rowReason != null && !rowReason.isBlank()) {
                changeRequest.setReason(truncate(rowReason.trim()));
            }
        } else {
            changeRequest.setStatus(STATUS_REJECTED);
            changeRequest.setFieldCheckResult(issues);
            changeRequest.setReason(issues.get(0).message());
        }
        changeRequest.setDeleted(0L);
        return changeRequest;
    }

    private Map<String, Object> payload(Long beforeCollegeId, Long beforeClassId,
                                        Long afterCollegeId, Long afterClassId) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("collegeId", beforeCollegeId);
        before.put("classId", beforeClassId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("collegeId", afterCollegeId);
        after.put("classId", afterClassId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("before", before);
        payload.put("after", after);
        return payload;
    }

    // ============ VO 回填（批量查 SysUser/College/SchoolClass） ============

    /**
     * 回填 payload_json / field_check_result。
     *
     * <p>实体未标注 {@code @TableName(autoResultMap = true)}（全代码库统一如此），
     * MyBatis-Plus 自动映射会把 JSON 列静默丢弃（实体读出来是 null），
     * 故按 id 批量取 JSON 原文（selectMaps 不经实体映射）再解析回填。</p>
     */
    private void attachPayloads(List<ChangeRequest> records) {
        if (records.isEmpty()) {
            return;
        }
        List<Long> ids = records.stream()
                .map(ChangeRequest::getId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rows = changeRequestMapper.selectMaps(Wrappers.<ChangeRequest>query()
                .select("id", "payload_json", "field_check_result")
                .in("id", ids)
                .eq("deleted", 0));
        if (rows.isEmpty()) {
            return;
        }
        Map<Long, ChangeRequest> byId = new HashMap<>();
        for (ChangeRequest record : records) {
            byId.put(record.getId(), record);
        }
        for (Map<String, Object> row : rows) {
            ChangeRequest record = byId.get(toLong(pick(row, "id")));
            if (record == null) {
                continue;
            }
            if (record.getPayloadJson() == null) {
                record.setPayloadJson(readJsonMap(pick(row, "payload_json")));
            }
            if (record.getFieldCheckResult() == null) {
                record.setFieldCheckResult(readJsonIssues(pick(row, "field_check_result")));
            }
        }
    }

    /** selectMaps 列名大小写随驱动而异（MySQL 小写 / H2 大写），兼容取列 */
    private static Object pick(Map<String, Object> row, String column) {
        Object value = row.get(column);
        return value != null ? value : row.get(column.toUpperCase(Locale.ROOT));
    }

    private Map<String, Object> readJsonMap(Object raw) {
        if (!(raw instanceof String json) || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("异动 payload_json 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private List<FieldCheckIssue> readJsonIssues(Object raw) {
        if (!(raw instanceof String json) || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<FieldCheckIssue>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("异动 field_check_result 解析失败: {}", e.getMessage());
            return null;
        }
    }

    private List<ChangeRequestVO> enrich(List<ChangeRequest> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        Set<Long> userIds = new HashSet<>();
        Set<Long> collegeIds = new HashSet<>();
        Set<Long> classIds = new HashSet<>();
        for (ChangeRequest record : records) {
            if (record.getTargetUserId() != null && record.getTargetUserId() > 0) {
                userIds.add(record.getTargetUserId());
            }
            if (record.getApplicantId() != null) {
                userIds.add(record.getApplicantId());
            }
            Belonging before = readBelonging(record.getPayloadJson(), "before");
            Belonging after = readBelonging(record.getPayloadJson(), "after");
            addIfNotNull(collegeIds, before.collegeId());
            addIfNotNull(collegeIds, after.collegeId());
            addIfNotNull(classIds, before.classId());
            addIfNotNull(classIds, after.classId());
        }
        Map<Long, SysUser> userMap = batchUsers(userIds);
        Map<Long, College> collegeMap = batchColleges(collegeIds);
        Map<Long, SchoolClass> classMap = batchClasses(classIds);
        List<ChangeRequestVO> vos = new ArrayList<>(records.size());
        for (ChangeRequest record : records) {
            vos.add(toVO(record, userMap, collegeMap, classMap));
        }
        return vos;
    }

    private ChangeRequestVO toVO(ChangeRequest record, Map<Long, SysUser> userMap,
                                 Map<Long, College> collegeMap, Map<Long, SchoolClass> classMap) {
        ChangeRequestVO vo = new ChangeRequestVO();
        vo.setId(record.getId());
        vo.setSemesterId(record.getSemesterId());
        vo.setType(record.getType());
        vo.setTargetUserId(record.getTargetUserId());
        SysUser target = userMap.get(record.getTargetUserId());
        if (target != null) {
            vo.setTargetUserNo(target.getUserNo());
            vo.setTargetUserName(target.getName());
        }
        Belonging before = readBelonging(record.getPayloadJson(), "before");
        Belonging after = readBelonging(record.getPayloadJson(), "after");
        vo.setBeforeCollegeId(before.collegeId());
        vo.setBeforeCollegeName(nameOf(collegeMap, before.collegeId()));
        vo.setBeforeClassId(before.classId());
        vo.setBeforeClassName(nameOf(classMap, before.classId()));
        vo.setAfterCollegeId(after.collegeId());
        vo.setAfterCollegeName(nameOf(collegeMap, after.collegeId()));
        vo.setAfterClassId(after.classId());
        vo.setAfterClassName(nameOf(classMap, after.classId()));
        vo.setStatus(record.getStatus());
        vo.setBatchNo(record.getBatchNo());
        vo.setApplicantId(record.getApplicantId());
        SysUser applicant = userMap.get(record.getApplicantId());
        vo.setApplicantName(applicant == null ? null : applicant.getName());
        vo.setReviewerId(record.getReviewerId());
        vo.setReason(record.getReason());
        vo.setFieldCheckResult(record.getFieldCheckResult());
        vo.setReviewAt(record.getReviewAt());
        vo.setCreatedAt(record.getCreatedAt());
        return vo;
    }

    private Map<Long, SysUser> batchUsers(Set<Long> ids) {
        Map<Long, SysUser> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (SysUser user : userMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                .in(SysUser::getId, ids)
                .eq(SysUser::getDeleted, 0))) {
            map.put(user.getId(), user);
        }
        return map;
    }

    private Map<Long, College> batchColleges(Set<Long> ids) {
        Map<Long, College> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (College college : collegeMapper.selectList(Wrappers.<College>lambdaQuery()
                .in(College::getId, ids)
                .eq(College::getDeleted, 0))) {
            map.put(college.getId(), college);
        }
        return map;
    }

    private Map<Long, SchoolClass> batchClasses(Set<Long> ids) {
        Map<Long, SchoolClass> map = new HashMap<>();
        if (ids.isEmpty()) {
            return map;
        }
        for (SchoolClass clazz : classMapper.selectList(Wrappers.<SchoolClass>lambdaQuery()
                .in(SchoolClass::getId, ids)
                .eq(SchoolClass::getDeleted, 0))) {
            map.put(clazz.getId(), clazz);
        }
        return map;
    }

    private String nameOf(Map<Long, ?> map, Long id) {
        if (id == null) {
            return null;
        }
        Object entity = map.get(id);
        if (entity instanceof College college) {
            return college.getName();
        }
        if (entity instanceof SchoolClass clazz) {
            return clazz.getName();
        }
        return null;
    }

    // ============ 归属快照 ============

    /** 目标用户当前归属：优先 active 学期 user_semester_profile，无 profile 回退 sys_user 冗余列 */
    private Belonging currentBelonging(Long targetUserId, Long semesterId) {
        if (targetUserId == null) {
            return new Belonging(null, null);
        }
        UserSemesterProfile profile = profileMapper.selectByUserAndSemester(targetUserId, semesterId);
        if (profile != null) {
            return new Belonging(profile.getCollegeId(), profile.getClassId());
        }
        SysUser user = userMapper.selectByIdSoft(targetUserId);
        if (user == null) {
            return new Belonging(null, null);
        }
        return new Belonging(user.getCollegeId(), user.getClassId());
    }

    private Belonging readBelonging(Map<String, Object> payload, String section) {
        if (payload != null && payload.get(section) instanceof Map<?, ?> inner) {
            return new Belonging(toLong(inner.get("collegeId")), toLong(inner.get("classId")));
        }
        return new Belonging(null, null);
    }

    private Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private record Belonging(Long collegeId, Long classId) {
    }

    // ============ 导入辅助 ============

    private List<ChangeImportRow> readRows(MultipartFile file) {
        try {
            // EasyExcel 3.x：默认监听器收集全部行，同步返回（空行由 EasyExcel 自动忽略）
            return EasyExcel.read(file.getInputStream())
                    .head(ChangeImportRow.class)
                    .sheet()
                    .doReadSync();
        } catch (IOException e) {
            log.warn("异动导入文件读取失败: {}", e.getMessage());
            throw new BizException(ErrorCode.FILE_TYPE_INVALID, "文件读取失败，请检查后重传");
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请选择上传文件");
        }
        String fileName = file.getOriginalFilename();
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new BizException(ErrorCode.FILE_TYPE_INVALID, "仅支持 .xlsx 文件");
        }
        long maxBytes = (long) properties.getImportConfig().getMaxFileMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new BizException(ErrorCode.FILE_TOO_LARGE,
                    "文件超过大小上限（" + properties.getImportConfig().getMaxFileMb() + "MB）");
        }
    }

    /** "CHG-" + yyyyMMdd + "-" + 6 位随机；撞号重试（与 change_request.batch_no 唯一） */
    private String generateBatchNo() {
        for (int i = 0; i < 3; i++) {
            String candidate = "CHG-" + LocalDate.now().format(BATCH_DATE) + "-"
                    + String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
            if (changeRequestMapper.selectByBatchNo(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new BizException(ErrorCode.BIZ_ERROR, "批次号生成失败，请重试");
    }

    /** 变更类型：兼容 student/teacher 与「学生/教师」；其余原样返回交 TYPE_VALID 行错误 */
    private String normalizeImportType(String raw) {
        String value = trimToEmpty(raw);
        if (TYPE_STUDENT.equalsIgnoreCase(value) || "学生".equals(value)) {
            return TYPE_STUDENT;
        }
        if (TYPE_TEACHER.equalsIgnoreCase(value) || "教师".equals(value)) {
            return TYPE_TEACHER;
        }
        return value;
    }

    private boolean isBlankRow(ChangeImportRow row) {
        return blank(row.getUserNo()) && blank(row.getType()) && blank(row.getCollegeName())
                && blank(row.getClassName()) && blank(row.getReason());
    }

    private Map<String, Object> rowErrorDetail(int rowNo, String userNo, List<FieldCheckIssue> issues) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("row", rowNo);
        detail.put("userNo", userNo);
        detail.put("issues", issues);
        return detail;
    }

    // ============ 通用校验/工具 ============

    private Semester requireActiveSemester() {
        Semester active = activeSemesterService.active();
        if (active == null) {
            throw new BizException(ErrorCode.BIZ_ERROR, "当前无激活学期，请先激活学期");
        }
        return active;
    }

    private String normalizeAction(String raw) {
        String value = trimToEmpty(raw);
        if (ACTION_PASS.equalsIgnoreCase(value)) {
            return ACTION_PASS;
        }
        if (ACTION_REJECT.equalsIgnoreCase(value)) {
            return ACTION_REJECT;
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "审批动作仅支持 pass/reject");
    }

    /** 驳回理由必填 1-200 字（PRD 模块 6）；pass 时忽略 */
    private String validateReason(String action, String reason) {
        if (ACTION_PASS.equals(action)) {
            return null;
        }
        String value = reason == null ? "" : reason.trim();
        if (value.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "驳回理由必填（1-200 字）");
        }
        if (value.length() > 200) {
            throw new BizException(ErrorCode.PARAM_INVALID, "驳回理由不能超过 200 字");
        }
        return value;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static void addIfNotNull(Set<Long> ids, Long id) {
        if (id != null) {
            ids.add(id);
        }
    }

    private static String truncate(String value) {
        return value.length() <= 200 ? value : value.substring(0, 200);
    }
}
