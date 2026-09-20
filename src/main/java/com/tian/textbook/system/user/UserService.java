package com.tian.textbook.system.user;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.auth.AuthUserService;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.system.dto.UserListItem;
import com.tian.textbook.system.entity.SysRole;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.entity.SysUserRole;
import com.tian.textbook.system.mapper.*;
import com.tian.textbook.semester.SemesterActiveService;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.entity.UserSemesterProfile;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 账号管理（PRD 模块 1 / SPEC §11.3）：建号（含供货商）、停用/启用（即时踢下线）、重置密码。
 *
 * <p>初始密码 = 学号/工号后 6 位（G1），must_change_password=1、first_login_verified=0；
 * 停用/重置均撤销 refresh 并 role_version+1 使旧 access 即时失效。</p>
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final AuthUserService authUserService;
    private final AuditService auditService;
    private final PasswordEncoder passwordEncoder;
    private final SemesterActiveService activeSemesterService;
    private final UserSemesterProfileMapper profileMapper;

    /** 初始密码：学号/工号后 6 位 */
    public static String initialPassword(String userNo) {
        String no = userNo == null ? "" : userNo.trim();
        return no.length() <= 6 ? no : no.substring(no.length() - 6);
    }

    @Transactional(readOnly = true)
    public PageResponse<UserListItem> page(String roleCode, Long collegeId, Integer status,
                                           String keyword, long page, long size) {
        long safeSize = Math.min(Math.max(size, 1), 200);
        long offset = (Math.max(page, 1) - 1) * safeSize;
        List<UserListItem> list = userMapper.selectPageByFilter(roleCode, collegeId, status, keyword, offset, safeSize);
        long total = userMapper.countByFilter(roleCode, collegeId, status, keyword);
        // 角色回填（页大小 ≤200，逐条查询可接受）
        for (UserListItem item : list) {
            item.setRoles(authUserService.loadRoles(item.getId()).stream().map(SysRole::getRoleCode).toList());
        }
        return PageResponse.of(list, Math.max(page, 1), safeSize, total);
    }

    /**
     * 建号（含供货商）：初始密码 + 待改密 + 首登未校验；
     * 指定了学院/班级时同步写入 active 学期的 user_semester_profile（W6）。
     */
    @Transactional
    public SysUser create(CreateUserRequest request) {
        String userNo = request.userNo().trim();
        if (userMapper.selectByUserNo(userNo) != null) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "账号已存在");
        }
        if (request.roleCodes() == null || request.roleCodes().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请至少分配一个角色");
        }
        SysUser user = new SysUser();
        user.setUserNo(userNo);
        user.setName(request.name().trim());
        user.setPhone(request.phone());
        user.setCollegeId(request.collegeId());
        user.setClassId(request.classId());
        user.setPasswordHash(passwordEncoder.encode(initialPassword(userNo)));
        user.setStatus(1);
        user.setMustChangePassword(1);
        user.setFirstLoginVerified(0);
        user.setFailCount(0);
        user.setRoleVersion(1);
        user.setDeleted(0L);
        userMapper.insert(user);
        bindRoles(user.getId(), request.roleCodes());
        // active 学期归属（手动建号视为本学期在册）
        Semester active = activeSemesterService.active();
        if (active != null && (request.collegeId() != null || request.classId() != null)) {
            upsertProfile(user.getId(), active.getId(), request.collegeId(), request.classId());
        }
        auditService.record(AuditService.ACCOUNT, "user", String.valueOf(user.getId()),
                Map.of("op", "create", "userNo", userNo, "roles", String.join(",", request.roleCodes())));
        authUserService.evict(user.getId());
        return user;
    }

    /** 停用/启用（停用即踢下线：撤销 refresh + role_version+1） */
    @Transactional
    public void updateStatus(Long id, int status) {
        SysUser user = userMapper.selectByIdSoft(id);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "账号不存在");
        }
        if (status == 0) {
            userMapper.disableById(id);
            userMapper.incrRoleVersion(id);
            userMapper.revokeAllTokens(id);
        } else {
            userMapper.enableById(id);
        }
        authUserService.evict(id);
        auditService.record(AuditService.ACCOUNT, "user", String.valueOf(id),
                Map.of("op", status == 0 ? "disable" : "enable", "userNo", user.getUserNo()));
    }

    /** 重置密码：重置为初始密码规则 + must_change_password=1，撤销 refresh 并踢下线 */
    @Transactional
    public void resetPassword(Long id) {
        SysUser user = userMapper.selectByIdSoft(id);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "账号不存在");
        }
        SysUser update = new SysUser();
        update.setId(id);
        update.setPasswordHash(passwordEncoder.encode(initialPassword(user.getUserNo())));
        update.setMustChangePassword(1);
        userMapper.update(update, Wrappers.<SysUser>lambdaUpdate().eq(SysUser::getId, id));
        userMapper.incrRoleVersion(id);
        userMapper.revokeAllTokens(id);
        authUserService.evict(id);
        auditService.record(AuditService.ACCOUNT, "user", String.valueOf(id),
                Map.of("op", "reset-password", "userNo", user.getUserNo()));
    }

    private void bindRoles(Long userId, List<String> roleCodes) {
        for (String roleCode : roleCodes) {
            SysRole role = roleMapper.selectByCode(roleCode.trim());
            if (role == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "角色不存在: " + roleCode);
            }
            if (userRoleMapper.selectByUserAndRole(userId, role.getId()) == null) {
                SysUserRole userRole = new SysUserRole();
                userRole.setUserId(userId);
                userRole.setRoleId(role.getId());
                userRole.setDeleted(0L);
                userRoleMapper.insert(userRole);
            }
        }
    }

    private void upsertProfile(Long userId, Long semesterId, Long collegeId, Long classId) {
        UserSemesterProfile existing = profileMapper.selectByUserAndSemester(userId, semesterId);
        LocalDateTime now = LocalDateTime.now();
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

    public record CreateUserRequest(
            @jakarta.validation.constraints.NotBlank(message = "学号/工号不能为空") String userNo,
            @jakarta.validation.constraints.NotBlank(message = "姓名不能为空") String name,
            String phone,
            Long collegeId,
            Long classId,
            @jakarta.validation.constraints.NotEmpty(message = "请至少分配一个角色") List<String> roleCodes) {
    }
}
