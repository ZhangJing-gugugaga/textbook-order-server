package com.tian.textbook.importexport.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.importexport.excel.StudentImportRow;
import com.tian.textbook.importexport.excel.TeacherCourseImportRow;
import com.tian.textbook.importexport.excel.TeacherImportRow;
import com.tian.textbook.importexport.excel.TextbookImportRow;
import com.tian.textbook.importexport.support.ImportRunContext;
import com.tian.textbook.semester.entity.UserSemesterProfile;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.system.entity.SchoolClass;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.entity.SysUserRole;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import com.tian.textbook.system.mapper.SysUserRoleMapper;
import com.tian.textbook.system.user.UserService;
import com.tian.textbook.textbook.entity.Course;
import com.tian.textbook.textbook.entity.TeacherCourse;
import com.tian.textbook.textbook.entity.Textbook;
import com.tian.textbook.textbook.mapper.CourseMapper;
import com.tian.textbook.textbook.mapper.TeacherCourseMapper;
import com.tian.textbook.textbook.mapper.TextbookMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 导入落库（幂等 upsert，SPEC §10 / §12）。
 *
 * <p>每个方法独立事务（REQUIRES_NEW）：每 500 行一个事务，失败仅回滚该批，错误行继续收集。
 * upsert 键：student/teacher = user_no；textbook = isbn；teacher_course =
 * (semester_id, teacher_id, course_id, class_id)。</p>
 *
 * <p>账号规则（W19/G1）：新建用户初始密码 = 学号/工号后 6 位、must_change_password=1、
 * first_login_verified=0；已存在用户只更新姓名/手机号（不重置密码、不改待改密标记），
 * 缺失角色时补绑（幂等）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportRowWriter {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final UserSemesterProfileMapper profileMapper;
    private final SchoolClassMapper schoolClassMapper;
    private final TextbookMapper textbookMapper;
    private final CourseMapper courseMapper;
    private final TeacherCourseMapper teacherCourseMapper;
    private final PasswordEncoder passwordEncoder;

    // ============ 学生全量 ============

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeStudentRows(List<StudentImportRow> rows, ImportRunContext ctx) {
        Long studentRoleId = ctx.roleId("STUDENT");
        for (StudentImportRow row : rows) {
            String userNo = row.getUserNo().trim();
            SysUser user = ctx.user(userNo);
            if (user == null) {
                SysUser created = new SysUser();
                created.setUserNo(userNo);
                created.setName(row.getName().trim());
                created.setPhone(blankToNull(row.getPhone()));
                created.setPasswordHash(passwordEncoder.encode(UserService.initialPassword(userNo)));
                created.setStatus(1);
                created.setMustChangePassword(1);
                created.setFirstLoginVerified(0);
                created.setFailCount(0);
                created.setRoleVersion(1);
                created.setDeleted(0L);
                userMapper.insert(created);
                bindRole(created.getId(), studentRoleId);
                ctx.putUser(created);
                user = created;
            } else {
                SysUser update = new SysUser();
                update.setId(user.getId());
                update.setName(row.getName().trim());
                update.setPhone(blankToNull(row.getPhone()));
                // 仅当目标学期即 active 学期才同步 sys_user 归属冗余列（SPEC §5.2：不污染 active 归属）
                if (ctx.writeUserAffiliation()) {
                    update.setCollegeId(row.getCollegeId());
                    update.setClassId(row.getClassId());
                }
                userMapper.update(update, Wrappers.<SysUser>lambdaUpdate().eq(SysUser::getId, user.getId()));
                bindRole(user.getId(), studentRoleId);
            }
            // 归属写目标学期 profile（导入为权威源，W14/SPEC §10）
            upsertProfile(user.getId(), ctx.semesterId(), row.getCollegeId(), row.getClassId());
        }
    }

    // ============ 教师全量 ============

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeTeacherRows(List<TeacherImportRow> rows, ImportRunContext ctx) {
        Long teacherRoleId = ctx.roleId("TEACHER");
        for (TeacherImportRow row : rows) {
            String userNo = row.getUserNo().trim();
            SysUser user = ctx.user(userNo);
            if (user == null) {
                SysUser created = new SysUser();
                created.setUserNo(userNo);
                created.setName(row.getName().trim());
                created.setPhone(blankToNull(row.getPhone()));
                created.setPasswordHash(passwordEncoder.encode(UserService.initialPassword(userNo)));
                created.setStatus(1);
                created.setMustChangePassword(1);
                created.setFirstLoginVerified(0);
                created.setFailCount(0);
                created.setRoleVersion(1);
                created.setDeleted(0L);
                userMapper.insert(created);
                bindRole(created.getId(), teacherRoleId);
                ctx.putUser(created);
                user = created;
            } else {
                SysUser update = new SysUser();
                update.setId(user.getId());
                update.setName(row.getName().trim());
                update.setPhone(blankToNull(row.getPhone()));
                if (ctx.writeUserAffiliation()) {
                    update.setCollegeId(row.getCollegeId());
                }
                userMapper.update(update, Wrappers.<SysUser>lambdaUpdate().eq(SysUser::getId, user.getId()));
                bindRole(user.getId(), teacherRoleId);
            }
            upsertProfile(user.getId(), ctx.semesterId(), row.getCollegeId(), null);
        }
    }

    // ============ 教材库（跨学期共用，semesterId 可空） ============

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeTextbookRows(List<TextbookImportRow> rows, ImportRunContext ctx) {
        for (TextbookImportRow row : rows) {
            String isbn = row.getIsbn();
            Textbook existing = textbookMapper.selectByIsbn(isbn);
            if (existing == null) {
                Textbook created = new Textbook();
                created.setIsbn(isbn);
                created.setTitle(row.getTitle().trim());
                created.setEdition(row.getEdition());
                created.setAuthor(row.getAuthor());
                created.setPress(row.getPress());
                created.setPrice(row.getPrice());
                created.setStatus(row.getStatus() == null ? 1 : row.getStatus());
                created.setDeleted(0L);
                textbookMapper.insert(created);
            } else {
                Textbook update = new Textbook();
                update.setId(existing.getId());
                update.setTitle(row.getTitle().trim());
                update.setEdition(row.getEdition());
                update.setAuthor(row.getAuthor());
                update.setPress(row.getPress());
                if (row.getPrice() != null) {
                    update.setPrice(row.getPrice());
                }
                update.setStatus(row.getStatus() == null ? 1 : row.getStatus());
                textbookMapper.update(update, Wrappers.<Textbook>lambdaUpdate()
                        .eq(Textbook::getId, existing.getId()));
            }
        }
    }

    // ============ 课程任课 ============

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeTeacherCourseRows(List<TeacherCourseImportRow> rows, ImportRunContext ctx) {
        for (TeacherCourseImportRow row : rows) {
            TeacherCourse existing = teacherCourseMapper.selectExact(
                    row.getSemesterId(), row.getTeacherId(), row.getCourseId(), row.getClassId());
            if (existing != null) {
                continue;
            }
            TeacherCourse created = new TeacherCourse();
            created.setSemesterId(row.getSemesterId());
            created.setTeacherId(row.getTeacherId());
            created.setCourseId(row.getCourseId());
            created.setClassId(row.getClassId());
            created.setDeleted(0L);
            teacherCourseMapper.insert(created);
        }
    }

    // ============ 收尾：班级人数重算 + 停用比对（W14） ============

    /**
     * 导入收尾（独立事务）：① 班级人数 = 文件内出现次数（W2）；
     * ② 停用比对仅限本次导入覆盖范围（文件内学院 + 角色 + status=1 中不在文件内的用户
     * 停用并置该学期 profile 不在册；范围外一律不动，R11）。
     *
     * @return 停用用户数（结果摘要 disabledCount）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int finishScope(ImportRunContext ctx) {
        for (Map.Entry<Long, Integer> entry : ctx.classCounts().entrySet()) {
            schoolClassMapper.update(null, Wrappers.<SchoolClass>lambdaUpdate()
                    .eq(SchoolClass::getId, entry.getKey())
                    .set(SchoolClass::getStudentCount, entry.getValue()));
        }
        if (!"student".equals(ctx.bizType()) && !"teacher".equals(ctx.bizType())) {
            return 0;
        }
        if (ctx.collegeIds().isEmpty() || ctx.semesterId() == null) {
            return 0;
        }
        String roleCode = "student".equals(ctx.bizType()) ? "STUDENT" : "TEACHER";
        Long roleId = ctx.roleId(roleCode);
        if (roleId == null) {
            log.warn("停用比对跳过：角色不存在 {}", roleCode);
            return 0;
        }
        List<SysUser> candidates = userMapper.selectActiveByCollegeIds(new ArrayList<>(ctx.collegeIds()));
        int disabled = 0;
        for (SysUser candidate : candidates) {
            if (ctx.userNos().contains(candidate.getUserNo())) {
                continue;
            }
            if (!userRoleMapper.selectRoleIdsByUser(candidate.getId()).contains(roleId)) {
                continue;
            }
            userMapper.disableById(candidate.getId());
            userMapper.incrRoleVersion(candidate.getId());
            userMapper.revokeAllTokens(candidate.getId());
            profileMapper.update(null, Wrappers.<UserSemesterProfile>lambdaUpdate()
                    .eq(UserSemesterProfile::getUserId, candidate.getId())
                    .eq(UserSemesterProfile::getSemesterId, ctx.semesterId())
                    .set(UserSemesterProfile::getStatus, 0));
            disabled++;
        }
        return disabled;
    }

    // ============ 私有 ============

    private void bindRole(Long userId, Long roleId) {
        if (roleId == null) {
            log.warn("角色缺失，跳过绑定: userId={}", userId);
            return;
        }
        if (userRoleMapper.selectByUserAndRole(userId, roleId) == null) {
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(roleId);
            userRole.setDeleted(0L);
            userRoleMapper.insert(userRole);
        }
    }

    private void upsertProfile(Long userId, Long semesterId, Long collegeId, Long classId) {
        if (semesterId == null) {
            return;
        }
        UserSemesterProfile existing = profileMapper.selectByUserAndSemester(userId, semesterId);
        if (existing == null) {
            UserSemesterProfile profile = new UserSemesterProfile();
            profile.setUserId(userId);
            profile.setSemesterId(semesterId);
            profile.setCollegeId(collegeId);
            profile.setClassId(classId);
            profile.setStatus(1);
            profile.setDeleted(0L);
            profileMapper.insert(profile);
        } else {
            UserSemesterProfile update = new UserSemesterProfile();
            update.setId(existing.getId());
            update.setCollegeId(collegeId);
            update.setClassId(classId);
            update.setStatus(1);
            profileMapper.update(update, Wrappers.<UserSemesterProfile>lambdaUpdate()
                    .eq(UserSemesterProfile::getId, existing.getId()));
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
