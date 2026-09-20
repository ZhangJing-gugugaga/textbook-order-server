package com.tian.textbook.support;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.system.entity.*;
import com.tian.textbook.system.mapper.*;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.entity.UserSemesterProfile;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.textbook.entity.Course;
import com.tian.textbook.textbook.entity.TeacherCourse;
import com.tian.textbook.textbook.entity.Textbook;
import com.tian.textbook.textbook.mapper.CourseMapper;
import com.tian.textbook.textbook.mapper.TeacherCourseMapper;
import com.tian.textbook.textbook.mapper.TextbookMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 集成测试数据工厂（Builder/工厂方法集中管理，避免散落魔法值）。
 *
 * <p>test profile 不执行 db/data-permission.sql（spring.sql.init.mode=never），
 * 故 {@link #seedRbac()} 按同源规则补种五角色 + 37 权限码 + 角色映射（幂等）。</p>
 */
@Component
@RequiredArgsConstructor
public class TestDataSeeder {

    /** 全量业务表（schema.sql 24 张，H2 无外键，清理顺序无关）；用例间全清保证隔离。 */
    private static final List<String> ALL_TABLES = List.of(
            "notice_record", "notice_task", "change_request", "import_batch", "export_task",
            "student_order_item", "student_order", "order_form_item", "order_form",
            "teacher_course", "course", "textbook", "user_semester_profile",
            "sys_user_role", "sys_role_permission", "sys_user_token", "sys_user",
            "school_class", "major", "college", "semester", "audit_log",
            "sys_role", "sys_permission", "system_config");

    /** 权限码清单（37 条 · M1 冻结 · 与 db/data-permission.sql 同源）。 */
    private static final String[][] PERMISSIONS = {
            {"semester:semester:manage", "学期新建/编辑", "semester"},
            {"semester:semester:activate", "学期激活（双缓冲切换）/归档", "semester"},
            {"semester:window:manage", "窗口设置/开启/提前截止/延长", "semester"},
            {"semester:window:view", "窗口状态查看（全角色）", "semester"},
            {"user:account:manage", "建号/停用/启用（含供货商）", "account"},
            {"user:account:reset", "重置密码", "account"},
            {"org:college:manage", "学院维护", "org"},
            {"org:major:manage", "专业维护", "org"},
            {"org:class:manage", "班级维护", "org"},
            {"textbook:book:manage", "教材 CRUD/停用", "textbook"},
            {"textbook:book:import", "教材导入", "textbook"},
            {"course:course:manage", "课程维护", "course"},
            {"course:teacher:manage", "任课关系维护/导入", "course"},
            {"people:student:import", "学生名单导入", "people"},
            {"people:teacher:import", "教师名单导入", "people"},
            {"order:form:submit", "教师填报提交/补正", "order"},
            {"order:form:view:self", "本人征订表单查看", "order"},
            {"order:form:view:college", "本院征订表单查看", "order"},
            {"order:form:view:all", "全院征订表单查看", "order"},
            {"order:form:review", "内容审核（通过/驳回）", "order"},
            {"student:order:submit", "学生选购提交", "student"},
            {"student:order:view:self", "本人选购查看", "student"},
            {"student:order:view:all", "全院选购查看", "student"},
            {"change:request:submit", "异动提交", "change"},
            {"change:request:review", "异动审批（含批量）", "change"},
            {"import:batch:view", "导入批次进度与错误明细", "batch"},
            {"export:order:create", "教师征订明细导出", "export"},
            {"export:signature:create", "秘书签字版导出", "export"},
            {"export:student:create", "学生选购汇总导出", "export"},
            {"export:notice:create", "通知汇总导出", "export"},
            {"notice:task:manage", "通知任务创建/关闭", "notice"},
            {"notice:task:view", "通知进度与失败名单", "notice"},
            {"dashboard:stat:view", "数据看板", "dashboard"},
            {"config:config:manage", "系统配置", "config"},
            {"audit:log:view", "审计日志查询", "audit"},
            {"supplier:order:view", "供货商清单查看", "supplier"},
            {"supplier:order:export", "供货商清单导出", "supplier"},
    };

    private static final Map<String, List<String>> ROLE_PERMISSIONS = rolePermissions();

    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permissionMapper;
    private final SysRolePermissionMapper rolePermissionMapper;
    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final CollegeMapper collegeMapper;
    private final MajorMapper majorMapper;
    private final SchoolClassMapper classMapper;
    private final UserSemesterProfileMapper profileMapper;
    private final SemesterMapper semesterMapper;
    private final TextbookMapper textbookMapper;
    private final CourseMapper courseMapper;
    private final TeacherCourseMapper teacherCourseMapper;
    private final JdbcTemplate jdbcTemplate;

    // ============ RBAC 种子（与 db/data-permission.sql 同源） ============

    /** 幂等：已有角色则不重复插入。 */
    public void seedRbac() {
        if (roleMapper.selectCount(null) > 0) {
            return;
        }
        insertRole("ADMIN", "教材室超管", 1);
        insertRole("SECRETARY", "学院秘书", 2);
        insertRole("TEACHER", "任课教师", 3);
        insertRole("STUDENT", "学生", 4);
        insertRole("SUPPLIER", "供货商", 5);
        for (String[] perm : PERMISSIONS) {
            SysPermission entity = new SysPermission();
            entity.setPermCode(perm[0]);
            entity.setPermName(perm[1]);
            entity.setModule(perm[2]);
            entity.setDeleted(0L);
            permissionMapper.insert(entity);
        }
        for (Map.Entry<String, List<String>> entry : ROLE_PERMISSIONS.entrySet()) {
            SysRole role = roleMapper.selectByCode(entry.getKey());
            for (String permCode : entry.getValue()) {
                SysPermission perm = permissionMapper.selectOne(Wrappers.<SysPermission>lambdaQuery()
                        .eq(SysPermission::getPermCode, permCode)
                        .eq(SysPermission::getDeleted, 0)
                        .last("LIMIT 1"));
                if (perm == null) {
                    continue;
                }
                SysRolePermission rp = new SysRolePermission();
                rp.setRoleId(role.getId());
                rp.setPermId(perm.getId());
                rp.setDeleted(0L);
                rolePermissionMapper.insert(rp);
            }
        }
    }

    private void insertRole(String code, String name, int sort) {
        SysRole role = new SysRole();
        role.setRoleCode(code);
        role.setRoleName(name);
        role.setSort(sort);
        role.setDeleted(0L);
        roleMapper.insert(role);
    }

    /** 清空全部业务表（用例间隔离；H2 mem 库跨用例共享，TRUNCATE 顺带重置自增 id）。 */
    public void cleanAll() {
        for (String table : ALL_TABLES) {
            jdbcTemplate.execute("TRUNCATE TABLE " + table);
        }
    }

    // ============ 组织 ============

    public College college(String name) {
        College college = new College();
        college.setName(name);
        college.setFullName(name + "（全称）");
        college.setDeleted(0L);
        collegeMapper.insert(college);
        return college;
    }

    public Major major(Long collegeId, String name) {
        Major major = new Major();
        major.setCollegeId(collegeId);
        major.setName(name);
        major.setDeleted(0L);
        majorMapper.insert(major);
        return major;
    }

    public SchoolClass schoolClass(Long majorId, String name, int studentCount) {
        SchoolClass clazz = new SchoolClass();
        clazz.setMajorId(majorId);
        clazz.setName(name);
        clazz.setGrade("2024");
        clazz.setStudentCount(studentCount);
        clazz.setDeleted(0L);
        classMapper.insert(clazz);
        return clazz;
    }

    // ============ 账号 ============

    public SysUser user(String userNo, String name, String phone, Long collegeId, Long classId,
                        int status, int mustChangePassword, int firstLoginVerified,
                        String... roleCodes) {
        SysUser user = new SysUser();
        user.setUserNo(userNo);
        user.setName(name);
        user.setPhone(phone);
        user.setCollegeId(collegeId);
        user.setClassId(classId);
        user.setPasswordHash("{noop}test");
        user.setStatus(status);
        user.setMustChangePassword(mustChangePassword);
        user.setFirstLoginVerified(firstLoginVerified);
        user.setFailCount(0);
        user.setRoleVersion(1);
        user.setDeleted(0L);
        userMapper.insert(user);
        bindRoles(user.getId(), roleCodes);
        return user;
    }

    public void bindRoles(Long userId, String... roleCodes) {
        for (String roleCode : roleCodes) {
            SysRole role = roleMapper.selectByCode(roleCode);
            if (role == null) {
                throw new IllegalStateException("角色不存在: " + roleCode);
            }
            if (userRoleMapper.selectByUserAndRole(userId, role.getId()) != null) {
                continue;
            }
            SysUserRole userRole = new SysUserRole();
            userRole.setUserId(userId);
            userRole.setRoleId(role.getId());
            userRole.setDeleted(0L);
            userRoleMapper.insert(userRole);
        }
    }

    public UserSemesterProfile profile(Long userId, Long semesterId, Long collegeId, Long classId) {
        return profile(userId, semesterId, collegeId, classId, 1);
    }

    public UserSemesterProfile profile(Long userId, Long semesterId, Long collegeId, Long classId,
                                       int status) {
        UserSemesterProfile profile = new UserSemesterProfile();
        profile.setUserId(userId);
        profile.setSemesterId(semesterId);
        profile.setCollegeId(collegeId);
        profile.setClassId(classId);
        profile.setStatus(status);
        profile.setDeleted(0L);
        profileMapper.insert(profile);
        return profile;
    }

    // ============ 学期域 ============

    public Semester semester(String name, LocalDate start, LocalDate end,
                             LocalDateTime windowStart, LocalDateTime windowEnd,
                             int autoOpen, int autoClose) {
        Semester semester = new Semester();
        semester.setName(name);
        semester.setStartDate(start);
        semester.setEndDate(end);
        semester.setWindowStart(windowStart);
        semester.setWindowEnd(windowEnd);
        semester.setAutoOpen(autoOpen);
        semester.setAutoClose(autoClose);
        semester.setChannelOpen(0);
        semester.setWindowStatus("not_open");
        semester.setActiveStatus("draft");
        semester.setVersion(0);
        semester.setDeleted(0L);
        semesterMapper.insert(semester);
        return semester;
    }

    public Textbook textbook(String isbn, String title, int status) {
        Textbook textbook = new Textbook();
        textbook.setIsbn(isbn);
        textbook.setTitle(title);
        textbook.setEdition("第1版");
        textbook.setAuthor("测试作者");
        textbook.setPress("测试出版社");
        textbook.setPrice(new BigDecimal("45.00"));
        textbook.setStatus(status);
        textbook.setDeleted(0L);
        textbookMapper.insert(textbook);
        return textbook;
    }

    public Course course(Long semesterId, String code, String name) {
        Course course = new Course();
        course.setSemesterId(semesterId);
        course.setCode(code);
        course.setName(name);
        course.setDeleted(0L);
        courseMapper.insert(course);
        return course;
    }

    public TeacherCourse teacherCourse(Long semesterId, Long teacherId, Long courseId, Long classId) {
        TeacherCourse relation = new TeacherCourse();
        relation.setSemesterId(semesterId);
        relation.setTeacherId(teacherId);
        relation.setCourseId(courseId);
        relation.setClassId(classId);
        relation.setDeleted(0L);
        teacherCourseMapper.insert(relation);
        return relation;
    }

    // ============ 查询辅助 ============

    /** 角色权限码（构造 CurrentUser 用）。 */
    public Set<String> permissionsOf(String roleCode) {
        SysRole role = roleMapper.selectByCode(roleCode);
        if (role == null) {
            return Set.of();
        }
        return Set.copyOf(roleMapper.selectPermCodesByRole(role.getId()));
    }

    public Long roleId(String roleCode) {
        SysRole role = roleMapper.selectByCode(roleCode);
        return role == null ? null : role.getId();
    }

    public Semester semesterByName(String name) {
        return semesterMapper.selectList(Wrappers.<Semester>lambdaQuery()
                        .eq(Semester::getName, name).eq(Semester::getDeleted, 0))
                .stream().findFirst().orElse(null);
    }

    private static Map<String, List<String>> rolePermissions() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("ADMIN", Arrays.stream(PERMISSIONS).map(p -> p[0])
                .filter(code -> !code.startsWith("supplier:")).toList());
        map.put("SECRETARY", List.of("semester:window:view", "order:form:view:college",
                "export:order:create", "export:signature:create", "change:request:submit",
                "import:batch:view"));
        map.put("TEACHER", List.of("semester:window:view", "order:form:submit",
                "order:form:view:self", "change:request:submit"));
        map.put("STUDENT", List.of("semester:window:view", "student:order:submit",
                "student:order:view:self"));
        map.put("SUPPLIER", List.of("supplier:order:view", "supplier:order:export"));
        return map;
    }
}
