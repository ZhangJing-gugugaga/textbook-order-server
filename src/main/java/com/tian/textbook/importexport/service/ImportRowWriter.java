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
        Map<String, String> passwordHashes = hashInitialPasswords(rows, ctx, StudentImportRow::getUserNo);
        for (StudentImportRow row : rows) {
            String userNo = row.getUserNo().trim();
            SysUser user = ctx.user(userNo);
            if (user == null) {
                SysUser created = new SysUser();
                created.setUserNo(userNo);
                created.setName(row.getName().trim());
                created.setPhone(blankToNull(row.getPhone()));
                created.setPasswordHash(passwordHashes.get(userNo));
                created.setStatus(1);
                created.setMustChangePassword(1);
                created.setFirstLoginVerified(0);
                created.setFailCount(0);
                created.setRoleVersion(1);
                created.setDeleted(0L);
                // 目标学期即 active 学期才写 sys_user 归属冗余列（与既有用户分支一致，SPEC §5.2）；
                // 不写则 W14 停用比对（按 college_id 圈范围）永远匹配不到新建用户
                if (ctx.writeUserAffiliation()) {
                    created.setCollegeId(row.getCollegeId());
                    created.setClassId(row.getClassId());
                }
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
        Map<String, String> passwordHashes = hashInitialPasswords(rows, ctx, TeacherImportRow::getUserNo);
        for (TeacherImportRow row : rows) {
            String userNo = row.getUserNo().trim();
            SysUser user = ctx.user(userNo);
            if (user == null) {
                SysUser created = new SysUser();
                created.setUserNo(userNo);
                created.setName(row.getName().trim());
                created.setPhone(blankToNull(row.getPhone()));
                created.setPasswordHash(passwordHashes.get(userNo));
                created.setStatus(1);
                created.setMustChangePassword(1);
                created.setFirstLoginVerified(0);
                created.setFailCount(0);
                created.setRoleVersion(1);
                created.setDeleted(0L);
                // 同 writeStudentRows：active 学期导入才写归属冗余列（W14 停用比对依赖）
                if (ctx.writeUserAffiliation()) {
                    created.setCollegeId(row.getCollegeId());
                }
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
     * 导入收尾（独立事务）：① 班级人数 = 文件内该班**去重**学生数（W2，以名单为准）；
     * ② 停用比对仅限本次导入覆盖范围（文件内学院 + 角色 + status=1 中不在文件内的用户
     * 停用并置该学期 profile 不在册；范围外一律不动，R11）。
     *
     * <p>语义说明（P0 复核项）：{@code school_class.student_count} 是教师征订数量上限的来源，
     * 由名单导入按「文件内该班人数」重算——与停用比对同一口径（文件即该范围的权威名单）。
     * 因此**局部名单会把上限改小**（文件里只放了 1 行 → 上限变 1），这是设计而非缺陷；
     * 为避免管理员在不知情的情况下卡住教师填报，人数被下调时记 WARN 并计入审计摘要
     * （{@code classSizeUpdates} / {@code classSizeShrinks}），必要时用
     * {@code PUT /api/admin/class/{id}} 的 {@code studentCount} 手工修正。</p>
     *
     * @return 收尾结果（停用数 + 班级人数更新统计），供批次审计与日志使用
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ScopeFinishResult finishScope(ImportRunContext ctx) {
        int updated = 0;
        int shrinks = 0;
        for (Map.Entry<Long, Integer> entry : ctx.classCounts().entrySet()) {
            Long classId = entry.getKey();
            int count = entry.getValue();
            if (count <= 0) {
                // 只统计到「文件内出现的班级」，条目必 ≥1；仍显式跳过 0 值，避免任何路径把上限清零
                continue;
            }
            SchoolClass before = schoolClassMapper.selectByIdSoft(classId);
            int previous = before == null || before.getStudentCount() == null ? 0 : before.getStudentCount();
            schoolClassMapper.update(null, Wrappers.<SchoolClass>lambdaUpdate()
                    .eq(SchoolClass::getId, classId)
                    .set(SchoolClass::getStudentCount, count));
            updated++;
            if (previous > count) {
                shrinks++;
                log.warn("班级人数被名单导入下调: classId={}, {} → {}（若为局部名单，"
                        + "教师数量上限会随之收紧，请用 PUT /api/admin/class/{} 手工修正）",
                        classId, previous, count, classId);
            }
        }
        if (!"student".equals(ctx.bizType()) && !"teacher".equals(ctx.bizType())) {
            return new ScopeFinishResult(0, updated, shrinks);
        }
        if (ctx.collegeIds().isEmpty() || ctx.semesterId() == null) {
            return new ScopeFinishResult(0, updated, shrinks);
        }
        // 停用比对只在「目标学期 = active 学期」时执行。
        //
        // 候选集来自 sys_user.college_id（该列是 **active 学期**的归属冗余列），而停用是全局的
        // （status=0 + role_version+1 + 撤销全部 refresh = 直接踢下线）。对 draft 学期做局部名单
        // 导入时，比对基准与目标学期错位：文件里没有的、属于当前 active 学期的在册学生/教师会被
        // 判为「不在名单」而全局停用——与 SPEC「新学期导入只写 draft 区与 profile，绝不污染
        // active 学期归属」冲突。draft/历史学期的名单导入只落 profile，不动账号状态。
        if (!ctx.writeUserAffiliation()) {
            log.info("停用比对跳过：目标学期非 active 学期（semesterId={}，仅写 profile 不动作账号状态）",
                    ctx.semesterId());
            return new ScopeFinishResult(0, updated, shrinks);
        }
        String roleCode = "student".equals(ctx.bizType()) ? "STUDENT" : "TEACHER";
        Long roleId = ctx.roleId(roleCode);
        if (roleId == null) {
            log.warn("停用比对跳过：角色不存在 {}", roleCode);
            return new ScopeFinishResult(0, updated, shrinks);
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
        return new ScopeFinishResult(disabled, updated, shrinks);
    }

    /**
     * 收尾结果。
     *
     * @param disabledCount     停用账号数
     * @param classSizeUpdates  按名单重算的班级数
     * @param classSizeShrinks  其中人数被下调的班级数（局部名单的信号，需人工确认是否需要修正）
     */
    public record ScopeFinishResult(int disabledCount, int classSizeUpdates, int classSizeShrinks) {
    }

    // ============ 私有 ============

    /**
     * 批量预哈希初始密码（SPEC §14 万行导入 ≤5 分钟）。
     *
     * <p>BCrypt(strength 10) 单次约 60-100ms，万行单线程串行需 10+ 分钟，远超验收预算；
     * 哈希是纯 CPU 计算、无 DB 访问，在批内并行（commonPool，8 核机约 6-8x）后回落至分钟级。
     * 重复 user_no 由 merge 函数去重。</p>
     *
     * <p><b>只为「本批将新建的账号」算</b>：已存在账号的密码不因导入而改变（见类注释），
     * 对它们预哈希是纯浪费——重复导入一份全量名单（绝大多数行是已存在账号）此前要白付
     * 一遍全量 BCrypt CPU。存在性判定走 {@link ImportRunContext#user(String)} 的缓存
     * （已按批预取，命中不查库）。</p>
     */
    private <T> Map<String, String> hashInitialPasswords(List<T> rows, ImportRunContext ctx,
                                                        java.util.function.Function<T, String> userNoOf) {
        return rows.parallelStream()
                .map(row -> userNoOf.apply(row).trim())
                .distinct()
                .filter(userNo -> ctx.user(userNo) == null)
                .collect(java.util.stream.Collectors.toConcurrentMap(
                        userNo -> userNo,
                        userNo -> passwordEncoder.encode(UserService.initialPassword(userNo))));
    }

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
