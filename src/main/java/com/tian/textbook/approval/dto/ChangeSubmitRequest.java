package com.tian.textbook.approval.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 异动逐条提交请求（SPEC §11.4：POST /api/teacher/change、POST /api/secretary/change）。
 *
 * <p>type=teacher 时仅允许变更学院（W16）：传了 targetClassId 直接 400 PARAM_INVALID；
 * 其余字段审查失败不抛错，落库为 rejected + field_check_result 供申请人查看原因。</p>
 */
public record ChangeSubmitRequest(
        /** 变更类型：student/teacher */
        @NotBlank(message = "变更类型不能为空") String type,
        /** 目标学号/工号 */
        @NotBlank(message = "目标学号/工号不能为空") String targetUserNo,
        /** 目标学院 id */
        @NotNull(message = "目标学院不能为空") Long targetCollegeId,
        /** 目标班级 id（仅 student 需要；teacher 传了即 400） */
        Long targetClassId) {
}
