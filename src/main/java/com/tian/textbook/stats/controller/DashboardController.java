package com.tian.textbook.stats.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.stats.dto.DashboardResponse;
import com.tian.textbook.stats.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据看板（SPEC §11.5：GET /api/admin/dashboard，dashboard:stat:view）。
 *
 * <p>窗口状态 + 各学院提交进度 + 待复核数 + 未确认通知数 + 学生选购统计，
 * 服务器时间（Asia/Shanghai）供前端倒计时。</p>
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final StatsService statsService;

    @GetMapping
    @PreAuthorize("hasAuthority('dashboard:stat:view')")
    public ApiResponse<DashboardResponse> dashboard() {
        return ApiResponse.ok(statsService.dashboard());
    }
}
