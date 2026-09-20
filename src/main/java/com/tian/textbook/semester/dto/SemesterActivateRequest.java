package com.tian.textbook.semester.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 双缓冲原子切换请求（body 带预期 version，乐观锁，W1）。
 */
public record SemesterActivateRequest(
        @NotNull(message = "version 不能为空") Integer version) {
}
