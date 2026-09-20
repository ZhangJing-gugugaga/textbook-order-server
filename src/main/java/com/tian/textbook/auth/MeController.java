package com.tian.textbook.auth;

import com.tian.textbook.auth.dto.AuthResponse;
import com.tian.textbook.auth.dto.ChangePasswordRequest;
import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.SecurityUtils;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.semester.SemesterActiveService;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.user.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 当前用户（/api/me/**，SPEC §11.1）。首登待改密状态下仅这些接口可用（W19）。
 *
 * <p>分层：聚合走 Service（AuthUserService / UserProfileService / SemesterActiveService），
 * Controller 不直连 Mapper（SPEC §2 机检红线）。</p>
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final AuthService authService;
    private final AuthUserService authUserService;
    private final UserProfileService userProfileService;
    private final SemesterActiveService activeSemesterService;

    /** 用户信息 + 角色列表 + 当前身份 + 授权状态 */
    @GetMapping
    public ApiResponse<Map<String, Object>> me() {
        var current = SecurityUtils.requireCurrentUser();
        SysUser user = authUserService.loadUser(current.userId());
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
            UserProfileService.SemesterBelonging belonging =
                    userProfileService.belongingOf(user.getId(), semesterId);
            if (belonging != null) {
                data.put("semesterId", semesterId);
                data.put("collegeId", belonging.collegeId());
                data.put("classId", belonging.classId());
                data.put("collegeName", belonging.collegeName());
                data.put("className", belonging.className());
            }
        }
        var active = activeSemesterService.active();
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
