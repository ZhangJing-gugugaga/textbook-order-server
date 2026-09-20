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
 *   <li>班级人数重算：班级 → 文件内出现次数。</li>
 * </ul>
 */
public class ImportRunContext {

    private final Long batchId;
    private final String bizType;
    private final Long semesterId;
    /** 目标学期即 active 学期时才同步 sys_user 归属冗余列（SPEC §5.2：不污染 active 归属） */
    private final boolean writeUserAffiliation;

    private final Set<String> userNos = new HashSet<>();
    private final Set<Long> collegeIds = new HashSet<>();
    private final Map<Long, Integer> classCounts = new HashMap<>();

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
        this.batchId = batchId;
        this.bizType = bizType;
        this.semesterId = semesterId;
        this.writeUserAffiliation = writeUserAffiliation;
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

    public Set<String> userNos() {
        return userNos;
    }

    public Set<Long> collegeIds() {
        return collegeIds;
    }

    public Map<Long, Integer> classCounts() {
        return classCounts;
    }

    public void recordUser(String userNo) {
        userNos.add(userNo);
    }

    public void recordCollege(Long collegeId) {
        collegeIds.add(collegeId);
    }

    public void recordClass(Long classId) {
        classCounts.merge(classId, 1, Integer::sum);
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
