package com.tian.textbook.system.config;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.system.entity.SystemConfig;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 系统配置管理（SPEC §11.5 契约基线）。
 */
@RestController
@RequestMapping("/api/admin/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;

    /** 配置列表 */
    @GetMapping
    @PreAuthorize("hasAuthority('config:config:manage')")
    public ApiResponse<List<SystemConfig>> list() {
        return ApiResponse.ok(configService.list());
    }

    /** 更新（键白名单 + 值域校验） */
    @PutMapping
    @PreAuthorize("hasAuthority('config:config:manage')")
    public ApiResponse<Void> update(@RequestBody Map<String, String> items) {
        configService.update(items);
        return ApiResponse.ok();
    }
}
