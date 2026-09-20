package com.tian.textbook.auth;

import com.tian.textbook.auth.dto.AuthResponse;
import com.tian.textbook.auth.dto.ChangePasswordRequest;
import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.SecurityUtils;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.system.entity.College;
import com.tian.textbook.system.entity.SchoolClass;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.mapper.CollegeMapper;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.entity.UserSemesterProfile;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前用户（/api/me/**，SPEC §11.1）。首登待改密状态下仅这些接口可用（W19）。
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final AuthService authService;
    private final AuthUserService authUserService;
    private final SysUserMapper userMapper;
    private final UserSemesterProfileMapper profileMapper;
    private final CollegeMapper collegeMapper;
    private final SchoolClassMapper classMapper;
    private final SemesterMapper semesterMapper;

    /** 用户信息 + 角色列表 + 当前身份 + 授权状态 */
    @GetMapping
    public ApiResponse<Map<String, Object>> me() {
        var current = SecurityUtils.requireCurrentUser();
        SysUser user = userMapper.selectByIdSoft(current.userId());
        if (user == null) {
            throw new BizException(ErrorCode.REFRESH_INVALID);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("userId", user.getId());
        data.put("userNo", user.getUserNo());
        data.put("name", user.getName());
        data.put("phone", user.getPhone());
        data.put("openidBound", user.getOpenid() != null && !user.getOpenid().isBlank());
        data.put("roles", current.roles());
        data.put("currentRole", current.currentRole());
        data.put("permissions", current.permissions());
        data.put("mustChangePassword", user.getMustChangePassword());
        data.put("firstLoginVerified", user.getFirstLoginVerified());
        // 当前 active 学期归属（真源 user_semester_profile，W6）
        Long semesterId = SemesterContextHolder.get();
        if (semesterId != null) {
            UserSemesterProfile profile = profileMapper.selectByUserAndSemester(user.getId(), semesterId);
            if (profile != null) {
                data.put("semesterId", semesterId);
                data.put("collegeId", profile.getCollegeId());
                data.put("classId", profile.getClassId());
                if (profile.getCollegeId() != null) {
                    College college = collegeMapper.selectByIdSoft(profile.getCollegeId());
                    data.put("collegeName", college == null ? null : college.getName());
                }
                if (profile.getClassId() != null) {
                    SchoolClass clazz = classMapper.selectByIdSoft(profile.getClassId());
                    data.put("className", clazz == null ? null : clazz.getName());
                }
            }
        }
        Semester active = semesterMapper.selectActive();
        data.put("activeSemester", active == null ? null : Map.of(
                "id", active.getId(), "name", active.getName(),
                "windowStatus", active.getWindowStatus(), "channelOpen", active.getChannelOpen()));
        return ApiResponse.ok(data);
    }

    /** 权限码列表（前端动态路由/菜单/v-perm 数据源） */
    @GetMapping("/permissions")
    public ApiResponse<List<String>> permissions() {
        var current = SecurityUtils.requireCurrentUser();
        return ApiResponse.ok(List.copyOf(current.permissions()));
    }

    /** 改密（改密后撤销全部 refresh 并重发） */
    @PutMapping("/password")
    public ApiResponse<AuthResponse> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                                    @RequestHeader(value = "X-Device-Id", required = false) String deviceId) {
        return ApiResponse.ok(authService.changePassword(request, deviceId));
    }
}
