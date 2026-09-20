package com.tian.textbook.semester.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * 窗口延长请求（无限次；延长早于当前时间被校验拦截）。
 */
public record WindowExtendRequest(
        @NotNull(message = "新的截止时间不能为空") LocalDateTime windowEnd) {
}
