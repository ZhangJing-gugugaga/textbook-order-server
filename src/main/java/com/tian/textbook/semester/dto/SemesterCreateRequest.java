package com.tian.textbook.semester.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 新建学期请求（draft）。
 */
public record SemesterCreateRequest(
        @NotBlank(message = "学期名称不能为空") String name,
        LocalDate startDate,
        LocalDate endDate,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        Integer autoOpen,
        Integer autoClose) {
}
