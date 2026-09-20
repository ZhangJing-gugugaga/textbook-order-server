package com.tian.textbook.semester.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 窗口设置请求（起止 + auto 开关；channel_open 总开关独立，SPEC §6）。
 */
public record WindowSetRequest(
        @NotNull(message = "窗口开始时间不能为空") LocalDateTime windowStart,
        @NotNull(message = "窗口截止时间不能为空") LocalDateTime windowEnd,
        Integer autoOpen,
        Integer autoClose) {
}
