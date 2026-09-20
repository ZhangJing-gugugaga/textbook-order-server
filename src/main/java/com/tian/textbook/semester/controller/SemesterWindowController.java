package com.tian.textbook.semester.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.semester.SemesterActiveService;
import com.tian.textbook.semester.entity.Semester;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 窗口状态查询（SPEC §11.2：GET /api/semester/window/status，含 serverTime，
 * 前端倒计时不信任本地时钟，W24）。
 */
@RestController
@RequestMapping("/api/semester")
@RequiredArgsConstructor
public class SemesterWindowController {

    private final SemesterActiveService activeSemesterService;

    @GetMapping("/window/status")
    @PreAuthorize("hasAuthority('semester:window:view')")
    public ApiResponse<Map<String, Object>> windowStatus() {
        Semester active = activeSemesterService.active();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("serverTime", ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalDateTime());
        if (active == null) {
            data.put("semesterId", null);
            data.put("windowStatus", null);
            return ApiResponse.ok(data);
        }
        data.put("semesterId", active.getId());
        data.put("semesterName", active.getName());
        data.put("windowStatus", active.getWindowStatus());
        data.put("windowStart", active.getWindowStart());
        data.put("windowEnd", active.getWindowEnd());
        data.put("channelOpen", active.getChannelOpen());
        data.put("activeStatus", active.getActiveStatus());
        return ApiResponse.ok(data);
    }
}
