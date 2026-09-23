package com.tian.textbook.approval.service;

import com.tian.textbook.approval.ChangeTypes;
import com.tian.textbook.approval.dto.ChangeImportRow;
import com.tian.textbook.approval.entity.ChangeRequest;
import com.tian.textbook.approval.mapper.ChangeRequestMapper;
import com.tian.textbook.common.CurrentUser;
import com.tian.textbook.common.FieldCheckIssue;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.semester.entity.UserSemesterProfile;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.system.entity.SchoolClass;
import com.tian.textbook.system.entity.SysRole;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.entity.SysUserRole;
import com.tian.textbook.system.mapper.CollegeMapper;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import com.tian.textbook.system.mapper.SysRoleMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import com.tian.textbook.system.mapper.SysUserRoleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 异动字段审查与记录构建（BE-7b 抽取）。
 *
 * <p>逐条提交（{@link ChangeRequestService#submit}）与批量导入（异步链路
 * {@link ChangeImportHandler}）**必须走同一套规则**——两处各写一份是历史教训：
 * 字段审查规则一旦漂移，同一条异动经导入与经提交会得到不同结论。</p>
 *
 * <p>规则集（SPEC §8）：TYPE_VALID / TARGET_EXISTS / COLLEGE_EXISTS / CLASS_EXISTS（仅 student）
 * / VALUE_CHANGED，外加范围校验 TARGET_ROLE_MISMATCH（角色与类型一致）与 TARGET_SCOPE
 * （非 ADMIN 只能对本院用户提交）。</p>
 */
@Component
@RequiredArgsConstructor
public class ChangeRecordSupport {

    public static final String TYPE_STUDENT = "student";
    public static final String TYPE_TEACHER = "teacher";
    public static final String STATUS_PENDING_REVIEW = "pending_review";
    public static final String STATUS_REJECTED = "rejected";

    /** 目标学号/工号无法解析时的占位 target_user_id（DDL NOT NULL，无外键；记录为 rejected 终态，不进审批） */
    public static final long UNSET_TARGET_USER_ID = 0L;

    private static final DateTimeFormatter BATCH_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int REASON_MAX = 200;

    private final ChangeRequestMapper changeRequestMapper;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final CollegeMapper collegeMapper;
    private final SchoolClassMapper classMapper;
    private final UserSemesterProfileMapper profileMapper;

    /** 归属（学院/班级）；无归属时两字段为 null */
    public record Belonging(Long collegeId, Long classId) {
    }

    // ============ 字段审查 ============

    /**
     * 异动字段审查（逐行执行，错误落 field_check_result）。
     *
     * @param classAmbiguous 目标班级同名多条（严格模式：导入行按 CLASS_EXISTS 行错误处理）
     */
    public List<FieldCheckIssue> fieldCheck(String type, SysUser target, Long targetCollegeId, Long targetClassId,
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

    /**
     * 目标范围校验（在 {@link #fieldCheck} 之上追加）：角色与类型一致 + 非 ADMIN 只能本院。
     *
     * <p>以字段审查错误（而非 400/403）表达，使逐条提交与批量导入行为一致：
     * 逐条 → rejected + 错误原因；批量 → 该行计入错误明细，不中断整批。</p>
     */
    public List<FieldCheckIssue> withScopeCheck(List<FieldCheckIssue> issues, String type, SysUser target,
                                               Belonging before, CurrentUser current, Long semesterId) {
        if (target == null) {
            return issues; // 目标不存在已由 TARGET_EXISTS 覆盖
        }
        List<FieldCheckIssue> result = new ArrayList<>(issues);
        if (TYPE_STUDENT.equals(type) || TYPE_TEACHER.equals(type)) {
            String requiredRole = TYPE_STUDENT.equals(type) ? "STUDENT" : "TEACHER";
            if (!rolesOf(target.getId()).contains(requiredRole)) {
                result.add(new FieldCheckIssue("targetUserNo", "TARGET_ROLE_MISMATCH",
                        TYPE_STUDENT.equals(type) ? "目标用户不是学生" : "目标用户不是教师"));
            }
        }
        if (!current.isAdmin()) {
            Long myCollege = profileMapper.selectCollegeId(current.userId(), semesterId);
            if (myCollege == null) {
                result.add(new FieldCheckIssue("targetUserNo", "TARGET_SCOPE",
                        "当前账号在该学期没有学院归属，无法提交异动"));
            } else if (!myCollege.equals(before.collegeId())) {
                result.add(new FieldCheckIssue("targetUserNo", "TARGET_SCOPE",
                        "只能对本院用户提交异动"));
            }
        }
        return result;
    }

    /** 目标用户的角色码集合（sys_user_role → sys_role）。 */
    public Set<String> rolesOf(Long userId) {
        List<SysUserRole> userRoles = userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                .eq(SysUserRole::getUserId, userId)
                .eq(SysUserRole::getDeleted, 0));
        if (userRoles.isEmpty()) {
            return Set.of();
        }
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).filter(Objects::nonNull).toList();
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        return roleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                        .in(SysRole::getId, roleIds)
                        .eq(SysRole::getDeleted, 0))
                .stream()
                .map(SysRole::getRoleCode)
                .collect(Collectors.toSet());
    }

    // ============ 归属快照 ============

    /** 目标用户当前归属：优先 active 学期 user_semester_profile，无 profile 回退 sys_user 冗余列 */
    public Belonging currentBelonging(Long targetUserId, Long semesterId) {
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

    // ============ 记录构建 ============

    /**
     * 构建 change_request：通过 → pending_review；失败 → rejected + field_check_result
     * + reason=首条错误信息。导入通过行的 reason 取行内「原因」。
     */
    public ChangeRequest buildChangeRequest(Long semesterId, Long applicantId, String type, Long targetUserId,
                                            Long targetCollegeId, Long targetClassId,
                                            Long beforeCollegeId, Long beforeClassId,
                                            List<FieldCheckIssue> issues, String batchNo, String rowReason,
                                            String changeType) {
        ChangeRequest changeRequest = new ChangeRequest();
        changeRequest.setSemesterId(semesterId);
        changeRequest.setType(type);
        changeRequest.setChangeType(changeType == null ? ChangeTypes.OTHER : changeType);
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

    public Map<String, Object> payload(Long beforeCollegeId, Long beforeClassId,
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

    /** "CHG-" + yyyyMMdd + "-" + 6 位随机；撞号重试（与 change_request.batch_no 唯一） */
    public String generateBatchNo() {
        for (int i = 0; i < 3; i++) {
            String candidate = "CHG-" + LocalDate.now().format(BATCH_DATE) + "-"
                    + String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));
            if (changeRequestMapper.selectByBatchNo(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new BizException(ErrorCode.BIZ_ERROR, "批次号生成失败，请重试");
    }

    // ============ 导入行工具 ============

    /** 变更对象：兼容 student/teacher 与「学生/教师」；其余原样返回交 TYPE_VALID 行错误 */
    public String normalizeImportType(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (TYPE_STUDENT.equalsIgnoreCase(value) || "学生".equals(value)) {
            return TYPE_STUDENT;
        }
        if (TYPE_TEACHER.equalsIgnoreCase(value) || "教师".equals(value)) {
            return TYPE_TEACHER;
        }
        return value;
    }

    /** 整行全空（跳过，不计入 total） */
    public boolean isBlankRow(ChangeImportRow row) {
        return blank(row.getUserNo()) && blank(row.getType()) && blank(row.getCollegeName())
                && blank(row.getClassName()) && blank(row.getReason()) && blank(row.getChangeType());
    }

    /** 错误明细条目（row/userNo/issues，供批次 error_detail 与错误明细 xlsx） */
    public Map<String, Object> rowErrorDetail(int rowNo, String userNo, List<FieldCheckIssue> issues) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("row", rowNo);
        detail.put("userNo", userNo);
        detail.put("issues", issues);
        return detail;
    }

    private static String truncate(String value) {
        if (value.length() <= REASON_MAX) {
            return value;
        }
        return value.substring(0, REASON_MAX - 1) + "…";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
