package com.tian.textbook.importexport.support;

import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.entity.UserSemesterProfile;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.system.entity.College;
import com.tian.textbook.system.entity.Major;
import com.tian.textbook.system.entity.SchoolClass;
import com.tian.textbook.system.entity.SysRole;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.mapper.CollegeMapper;
import com.tian.textbook.system.mapper.MajorMapper;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import com.tian.textbook.system.mapper.SysRoleMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import com.tian.textbook.system.mapper.SysUserRoleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单次导入运行的上下文（每批次一个实例，仅在解析线程内使用）。
 *
 * <p>职责：</p>
 * <ul>
 *   <li>外键查找缓存（学院/专业/班级/用户/学期/角色），万行导入把重复查询降到 O(去重实体数)；</li>
 *   <li>停用比对范围（W14）：文件内 user_no 集合 + 文件内学院集合；</li>
 *   <li>班级人数重算：班级 → 文件内**去重学生学号**集合（重复行不重复计数，见 {@link #recordClass}）。</li>
 * </ul>
 */
public class ImportRunContext {

    private final Long batchId;
    private final String bizType;
    private final Long semesterId;
    /** 目标学期即 active 学期时才同步 sys_user 归属冗余列（SPEC §5.2：不污染 active 归属） */
    private final boolean writeUserAffiliation;
    /**
     * 调用方是否已确认「班级人数下调超阈值」（B13 局部名单防护）。
     * 仅用于审计留痕：确认为真时批次摘要记 {@code classSizeShrinkConfirmed=true}，
     * 便于事后追溯「这次把上限压小是管理员明确点过的」。
     */
    private final boolean classSizeShrinkConfirmed;

    private final Set<String> userNos = new HashSet<>();
    private final Set<Long> collegeIds = new HashSet<>();
    /** 班级 → 文件内出现的学生学号（用 Set 去重：同一学号出现多行只算 1 人） */
    private final Map<Long, Set<String>> classMembers = new HashMap<>();

    private final Map<String, College> collegeCache = new HashMap<>();
    private final Map<String, Major> majorCache = new HashMap<>();
    private final Map<String, SchoolClass> classCache = new HashMap<>();
    private final Map<String, List<SchoolClass>> classByNameCache = new HashMap<>();
    private final Map<Long, Long> majorCollegeCache = new HashMap<>();
    private final Map<String, SysUser> userCache = new HashMap<>();
    private final Map<String, Semester> semesterCache = new HashMap<>();
    private final Map<String, Long> roleIdCache = new HashMap<>();
    private final Map<String, Boolean> userRoleCache = new HashMap<>();
    private final Map<String, Long> userCollegeCache = new HashMap<>();

    private final CollegeMapper collegeMapper;
    private final MajorMapper majorMapper;
    private final SchoolClassMapper schoolClassMapper;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SemesterMapper semesterMapper;
    private final UserSemesterProfileMapper profileMapper;

    public ImportRunContext(Long batchId, String bizType, Long semesterId, boolean writeUserAffiliation,
                            CollegeMapper collegeMapper, MajorMapper majorMapper, SchoolClassMapper schoolClassMapper,
                            SysUserMapper userMapper, SysRoleMapper roleMapper, SysUserRoleMapper userRoleMapper,
                            SemesterMapper semesterMapper, UserSemesterProfileMapper profileMapper) {
        this(batchId, bizType, semesterId, writeUserAffiliation, false, collegeMapper, majorMapper,
                schoolClassMapper, userMapper, roleMapper, userRoleMapper, semesterMapper, profileMapper);
    }

    public ImportRunContext(Long batchId, String bizType, Long semesterId, boolean writeUserAffiliation,
                            boolean classSizeShrinkConfirmed,
                            CollegeMapper collegeMapper, MajorMapper majorMapper, SchoolClassMapper schoolClassMapper,
                            SysUserMapper userMapper, SysRoleMapper roleMapper, SysUserRoleMapper userRoleMapper,
                            SemesterMapper semesterMapper, UserSemesterProfileMapper profileMapper) {
        this.batchId = batchId;
        this.bizType = bizType;
        this.semesterId = semesterId;
        this.writeUserAffiliation = writeUserAffiliation;
        this.classSizeShrinkConfirmed = classSizeShrinkConfirmed;
        this.collegeMapper = collegeMapper;
        this.majorMapper = majorMapper;
        this.schoolClassMapper = schoolClassMapper;
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.userRoleMapper = userRoleMapper;
        this.semesterMapper = semesterMapper;
        this.profileMapper = profileMapper;
    }

    public Long batchId() {
        return batchId;
    }

    public String bizType() {
        return bizType;
    }

    public Long semesterId() {
        return semesterId;
    }

    public boolean writeUserAffiliation() {
        return writeUserAffiliation;
    }

    public boolean classSizeShrinkConfirmed() {
        return classSizeShrinkConfirmed;
    }

    public Set<String> userNos() {
        return userNos;
    }

    public Set<Long> collegeIds() {
        return collegeIds;
    }

    /** 班级 → 该班在本次文件中的**去重**学生人数（W2 班级人数来源） */
    public Map<Long, Integer> classCounts() {
        Map<Long, Integer> counts = new HashMap<>(classMembers.size());
        classMembers.forEach((classId, members) -> counts.put(classId, members.size()));
        return counts;
    }

    public void recordUser(String userNo) {
        userNos.add(userNo);
    }

    public void recordCollege(Long collegeId) {
        collegeIds.add(collegeId);
    }

    /**
     * 记录班级名单成员（学生名单导入专用，W2 班级人数来源）。
     *
     * <p>按学号去重：同一学号在文件里出现多行只算 1 人。此前按「行数」计数，
     * 重复行会把班级人数算大（进而把教师数量上限放大到不存在的规模）。</p>
     */
    public void recordClass(Long classId, String userNo) {
        classMembers.computeIfAbsent(classId, key -> new HashSet<>()).add(userNo);
    }

    // ============ 缓存外键查找 ============

    public College college(String name) {
        return collegeCache.computeIfAbsent(name, collegeMapper::selectByName);
    }

    public Major major(Long collegeId, String name) {
        return majorCache.computeIfAbsent(collegeId + "|" + name,
                k -> majorMapper.selectByCollegeAndName(collegeId, name));
    }

    public SchoolClass schoolClass(Long majorId, String name) {
        return classCache.computeIfAbsent(majorId + "|" + name,
                k -> schoolClassMapper.selectByMajorAndName(majorId, name));
    }

    /** 全局按名称找班级（任课/异动导入；同名多班时由调用方择优） */
    public List<SchoolClass> classesByName(String name) {
        return classByNameCache.computeIfAbsent(name, n -> schoolClassMapper.selectList(
                Wrappers.<SchoolClass>lambdaQuery()
                        .eq(SchoolClass::getName, n)
                        .eq(SchoolClass::getDeleted, 0)));
    }

    /** 班级所属学院（经专业） */
    public Long collegeOfClass(SchoolClass clazz) {
        return majorCollegeCache.computeIfAbsent(clazz.getMajorId(), majorId -> {
            Major major = majorMapper.selectByIdSoft(majorId);
            return major == null ? null : major.getCollegeId();
        });
    }

    public SysUser user(String userNo) {
        return userCache.computeIfAbsent(userNo, userMapper::selectByUserNo);
    }

    /** 新建用户后刷新缓存，避免同文件重复 user_no 二次插入 */
    public void putUser(SysUser user) {
        userCache.put(user.getUserNo(), user);
    }

    public Semester semester(String name) {
        return semesterCache.computeIfAbsent(name, n -> semesterMapper.selectList(
                Wrappers.<Semester>lambdaQuery()
                        .eq(Semester::getName, n)
                        .eq(Semester::getDeleted, 0)
                        .last("LIMIT 1")).stream().findFirst().orElse(null));
    }

    public Long roleId(String roleCode) {
        return roleIdCache.computeIfAbsent(roleCode, code -> {
            SysRole role = roleMapper.selectByCode(code);
            return role == null ? null : role.getId();
        });
    }

    public boolean userHasRole(Long userId, String roleCode) {
        Long roleId = roleId(roleCode);
        if (roleId == null) {
            return false;
        }
        return userRoleCache.computeIfAbsent(userId + "|" + roleCode,
                k -> userRoleMapper.selectRoleIdsByUser(userId).contains(roleId));
    }

    /** 用户在指定学期的学院归属（profile 真源，W6） */
    public Long collegeOfUser(Long userId, Long semesterId) {
        return userCollegeCache.computeIfAbsent(userId + "|" + semesterId, k -> {
            UserSemesterProfile profile = profileMapper.selectByUserAndSemester(userId, semesterId);
            return profile == null ? null : profile.getCollegeId();
        });
    }
}
