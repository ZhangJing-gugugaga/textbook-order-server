package com.tian.textbook.semester.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 编辑学期基本信息请求。
 */
public record SemesterUpdateRequest(
        String name,
        LocalDate startDate,
        LocalDate endDate,
        LocalDateTime windowStart,
        LocalDateTime windowEnd,
        Integer autoOpen,
        Integer autoClose) {
}
