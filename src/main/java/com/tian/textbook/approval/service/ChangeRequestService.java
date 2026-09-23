package com.tian.textbook.approval.service;

import com.tian.textbook.common.util.AppTime;
import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tian.textbook.approval.ChangeTypes;
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
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.system.entity.College;
import com.tian.textbook.system.entity.SchoolClass;
import com.tian.textbook.system.entity.SysRole;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.entity.SysUserRole;
import com.tian.textbook.system.mapper.CollegeMapper;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import com.tian.textbook.system.mapper.SysRoleMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import com.tian.textbook.system.mapper.SysUserRoleMapper;
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

    /** 单次批量审批条数上限（整批单事务，防止一次请求长时间持有连接与行锁） */
    private static final int MAX_BATCH_REVIEW = 500;

    private final ChangeRequestMapper changeRequestMapper;
    /** 字段审查与记录构建（与异步导入共用，BE-7b 抽取） */
    private final ChangeRecordSupport support;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final CollegeMapper collegeMapper;
    private final SchoolClassMapper classMapper;
    private final UserSemesterProfileMapper profileMapper;
    private final ImportBatchMapper importBatchMapper;
    private final SemesterMapper semesterMapper;
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
        ChangeRecordSupport.Belonging before = support.currentBelonging(target == null ? null : target.getId(), active.getId());
        List<FieldCheckIssue> issues = support.fieldCheck(type, target, request.targetCollegeId(),
                request.targetClassId(), before.collegeId(), before.classId(), false);
        issues = support.withScopeCheck(issues, type, target, before, current, active.getId());

        ChangeRequest changeRequest = support.buildChangeRequest(active.getId(), current.userId(), type,
                target == null ? null : target.getId(), request.targetCollegeId(), request.targetClassId(),
                before.collegeId(), before.classId(), issues, null, null,
                ChangeTypes.parse(request.changeType()));
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

    // ============ 我的提交记录 ============

    /** 我的提交记录（applicant_id = 本人，@CollegeScope 数据隔离），回填目标用户/学院/班级名称 */
    @Transactional(readOnly = true)
    public List<ChangeRequestVO> myRequests() {
        CurrentUser current = SecurityUtils.requireCurrentUser();
        List<ChangeRequest> records = changeRequestMapper.selectMyRequests(current.userId());
        attachPayloads(records);
        return enrich(records);
    }

    /**
     * 异动提交端的目标归属选项：学院与班级的只读清单。
     *
     * <p>只返回 id + 名称（不含 student_count 等管理字段），供提交表单的下拉选择；
     * 组织三表维护接口仍为超管专属（org:*:manage）。</p>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> orgOptions() {
        List<Map<String, Object>> colleges = collegeMapper.selectList(
                        Wrappers.<College>lambdaQuery().eq(College::getDeleted, 0).orderByAsc(College::getId))
                .stream()
                .map(c -> Map.<String, Object>of("id", c.getId(), "name", c.getName()))
                .toList();
        List<Map<String, Object>> classes = classMapper.selectList(
                        Wrappers.<SchoolClass>lambdaQuery().eq(SchoolClass::getDeleted, 0).orderByAsc(SchoolClass::getId))
                .stream()
                .map(k -> Map.<String, Object>of("id", k.getId(), "name", k.getName(), "majorId", k.getMajorId()))
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("colleges", colleges);
        result.put("classes", classes);
        return result;
    }

    // ============ 审批列表（超管） ============

    /** 审批列表分页（semesterId/status/batchNo/type 过滤） */
    @Transactional(readOnly = true)
    public PageResponse<ChangeRequestListItem> page(Long semesterId, String status, String batchNo, String type,
                                                    String changeType, long page, long size) {
        long safeSize = PageResponse.normalizeSize(size);
        long safePage = PageResponse.normalizePage(page);
        long offset = (safePage - 1) * safeSize;
        String safeChangeType = trimToEmpty(changeType);
        List<ChangeRequestListItem> list = changeRequestMapper.selectPageByFilter(
                semesterId, trimToEmpty(status), trimToEmpty(batchNo), trimToEmpty(type),
                safeChangeType, offset, safeSize);
        // 异动类型中文回填（历史数据 change_type 为 NULL → 前端展示「未分类」）
        list.forEach(item -> item.setChangeTypeLabel(ChangeTypes.labelOf(item.getChangeType())));
        long total = changeRequestMapper.countByFilter(
                semesterId, trimToEmpty(status), trimToEmpty(batchNo), trimToEmpty(type), safeChangeType);
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
        // 单次批量上限：整批在同一事务内逐条推进，无上限时一次请求可长时间持有连接与行锁
        // （超限请分批提交，批内条目由导入时的 batch_no 决定）
        if (pending.size() > MAX_BATCH_REVIEW) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "该批次待审 " + pending.size() + " 条，超过单次审批上限 " + MAX_BATCH_REVIEW + " 条，请分批处理");
        }
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
     * 审批一条：pass → 写该异动所属学期 user_semester_profile（student 改 college_id+class_id，
     * teacher 只改 college_id，W16）+ 审计；reject → reason + 审计。
     *
     * <p>并发保护：状态推进以 CAS（{@code status='pending_review'} 作谓词）完成，
     * 两个管理员同时审批只有一个成功，后到者 409；此前只按 id 更新会让后写覆盖先写、
     * 并产生重复审计记录。CAS 通过后再生效落库——同一事务内，落库失败即整体回滚。</p>
     *
     * <p>入参实体须已 {@link #attachPayloads} 回填 payload（审批通过读 after 值生效落库）。</p>
     */
    private void applyReview(ChangeRequest changeRequest, String action, String reason, CurrentUser current) {
        Long id = changeRequest.getId();
        boolean pass = ACTION_PASS.equals(action);
        LocalDateTime reviewAt = AppTime.now();
        String nextStatus = pass ? STATUS_APPROVED : STATUS_REJECTED;

        ChangeRequest update = new ChangeRequest();
        update.setId(id);
        update.setStatus(nextStatus);
        if (!pass) {
            update.setReason(reason);
        }
        update.setReviewerId(current.userId());
        update.setReviewAt(reviewAt);
        int rows = changeRequestMapper.update(update, Wrappers.<ChangeRequest>lambdaUpdate()
                .eq(ChangeRequest::getId, id)
                .eq(ChangeRequest::getStatus, STATUS_PENDING_REVIEW));
        if (rows == 0) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "存在更新的异动状态，请刷新后重试");
        }
        changeRequest.setStatus(nextStatus);
        changeRequest.setReviewerId(current.userId());
        changeRequest.setReviewAt(reviewAt);
        if (!pass) {
            changeRequest.setReason(reason);
        }
        if (pass) {
            applyToProfile(changeRequest);
        }
        // 审批落库与审计同事务（SPEC §12）
        auditService.record(AuditService.CHANGE, "change", String.valueOf(id),
                Map.of("action", action, "targetUserId", String.valueOf(changeRequest.getTargetUserId())));
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
            ChangeRecordSupport.Belonging before = readBelonging(record.getPayloadJson(), "before");
            ChangeRecordSupport.Belonging after = readBelonging(record.getPayloadJson(), "after");
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
        vo.setChangeType(record.getChangeType());
        vo.setChangeTypeLabel(ChangeTypes.labelOf(record.getChangeType()));
        vo.setTargetUserId(record.getTargetUserId());
        SysUser target = userMap.get(record.getTargetUserId());
        if (target != null) {
            vo.setTargetUserNo(target.getUserNo());
            vo.setTargetUserName(target.getName());
        }
        ChangeRecordSupport.Belonging before = readBelonging(record.getPayloadJson(), "before");
        ChangeRecordSupport.Belonging after = readBelonging(record.getPayloadJson(), "after");
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

    /**
     * 生效落库（W15）：upsert <b>该异动所属学期</b>的 user_semester_profile，
     * 并在该学期仍是 active 学期时同步 sys_user 冗余列 college_id/class_id
     * （SPEC §7：冗余列由 profile 同步）。
     * teacher 仅更新 college_id（class_id 保持原值，MP 非空字段更新策略天然跳过 null）。
     *
     * <p>两个历史缺陷在此修正：</p>
     * <ul>
     *   <li><b>学期来源</b>：原实现用 {@code requireActiveSemester()}。异动单提交后管理员
     *       切换学期（常规操作，待审记录会跨切换留存）再审批，归属就被写进新学期，
     *       旧学期归属保持陈旧、新学期归属被凭空写入。现改为 {@code changeRequest.getSemesterId()}；</li>
     *   <li><b>清空归属</b>：payload 解析失败时 after.collegeId 为 null，原实现直接
     *       {@code set(collegeId, null)} 把用户 college_id 置空（MyBatis-Plus 两参 set 无条件写 NULL），
     *       叠加 CollegeScopeHandler 的 {@code 1 = 0} 使该用户此后查不到任何数据。
     *       现改为校验失败即抛异常终止审批。</li>
     * </ul>
     *
     * <p>注意：/api/me 与 /api/admin/user 读的是 sys_user 冗余列，若只写 profile，
     * 审批通过后这些接口会一直回显异动前的学院/班级（本方法两处同写，避免归属不一致）。</p>
     */
    private void applyToProfile(ChangeRequest changeRequest) {
        Long semesterId = changeRequest.getSemesterId();
        if (semesterId == null) {
            throw new BizException(ErrorCode.BIZ_ERROR, "异动记录缺少所属学期，无法生效，请驳回后重新提交");
        }
        if (semesterMapper.selectByIdSoft(semesterId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "异动所属学期不存在，无法生效");
        }
        ChangeRecordSupport.Belonging after = readBelonging(changeRequest.getPayloadJson(), "after");
        boolean isStudent = TYPE_STUDENT.equals(changeRequest.getType());
        if (after.collegeId() == null) {
            throw new BizException(ErrorCode.BIZ_ERROR, "异动目标学院缺失（payload 解析失败），无法生效，请驳回后重新提交");
        }
        if (isStudent && after.classId() == null) {
            throw new BizException(ErrorCode.BIZ_ERROR, "异动目标班级缺失（payload 解析失败），无法生效，请驳回后重新提交");
        }
        Long targetUserId = changeRequest.getTargetUserId();
        UserSemesterProfile existing = profileMapper.selectByUserAndSemester(targetUserId, semesterId);
        if (existing == null) {
            UserSemesterProfile profile = new UserSemesterProfile();
            profile.setUserId(targetUserId);
            profile.setSemesterId(semesterId);
            profile.setCollegeId(after.collegeId());
            profile.setClassId(isStudent ? after.classId() : null);
            profile.setStatus(1);
            profile.setDeleted(0L);
            profileMapper.insert(profile);
        } else {
            UserSemesterProfile update = new UserSemesterProfile();
            update.setId(existing.getId());
            update.setCollegeId(after.collegeId());
            if (isStudent) {
                update.setClassId(after.classId());
            }
            profileMapper.update(update, Wrappers.<UserSemesterProfile>lambdaUpdate()
                    .eq(UserSemesterProfile::getId, existing.getId()));
        }
        // sys_user 冗余列只描述「当前 active 学期归属」：异动所属学期已不是 active 时
        // 写它会污染当前学期的显示（旧学期归属改完，新学期界面跟着变）。
        if (semesterId.equals(activeSemesterService.activeId())) {
            syncRedundantColumns(targetUserId, after.collegeId(), isStudent ? after.classId() : null);
        } else {
            log.info("异动生效于非 active 学期，跳过 sys_user 冗余列同步: changeId={}, semesterId={}",
                    changeRequest.getId(), semesterId);
        }
    }


    /** 同步 sys_user 冗余归属列（/api/me、/api/admin/user 的数据来源）。 */
    private void syncRedundantColumns(Long userId, Long collegeId, Long classId) {
        if (userId == null || userId == UNSET_TARGET_USER_ID) {
            return;
        }
        userMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getDeleted, 0)
                .set(SysUser::getCollegeId, collegeId)
                .set(classId != null, SysUser::getClassId, classId));
    }


    private ChangeRecordSupport.Belonging readBelonging(Map<String, Object> payload, String section) {
        if (payload != null && payload.get(section) instanceof Map<?, ?> inner) {
            return new ChangeRecordSupport.Belonging(toLong(inner.get("collegeId")), toLong(inner.get("classId")));
        }
        return new ChangeRecordSupport.Belonging(null, null);
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
