package com.tian.textbook.system.user;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.system.dto.UserListItem;
import com.tian.textbook.system.user.UserService.CreateUserRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 账号管理（SPEC §11.3 契约基线，/api/admin/user/**）。
 */
@RestController
@RequestMapping("/api/admin/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /** 账号检索（角色/学院/状态/关键字） */
    @GetMapping
    @PreAuthorize("hasAuthority('user:account:manage')")
    public ApiResponse<PageResponse<UserListItem>> page(
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) Long collegeId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(userService.page(roleCode, collegeId, status, keyword, page, size));
    }

    /** 建号（含供货商） */
    @PostMapping
    @PreAuthorize("hasAuthority('user:account:manage')")
    public ApiResponse<Void> create(@Valid @RequestBody CreateUserRequest request) {
        userService.create(request);
        return ApiResponse.ok();
    }

    /** 停用/启用（停用即时踢下线） */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('user:account:manage')")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @RequestParam int status) {
        userService.updateStatus(id, status);
        return ApiResponse.ok();
    }

    /** 重置密码（重置为初始密码规则 + 待改密） */
    @PutMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('user:account:reset')")
    public ApiResponse<Void> resetPassword(@PathVariable Long id) {
        userService.resetPassword(id);
        return ApiResponse.ok();
    }
}
