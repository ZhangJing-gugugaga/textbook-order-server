package com.tian.textbook.approval.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 异动逐条提交请求（SPEC §11.4：POST /api/teacher/change、POST /api/secretary/change）。
 *
 * <p>type=teacher 时仅允许变更学院（W16）：传了 targetClassId 直接 400 PARAM_INVALID；
 * 其余字段审查失败不抛错，落库为 rejected + field_check_result 供申请人查看原因。</p>
 *
 * <p>@Size 与 DDL 列宽一致（sys_user.user_no VARCHAR(32)）。</p>
 */
public record ChangeSubmitRequest(
        /** 变更类型：student/teacher */
        @NotBlank(message = "变更类型不能为空") @Size(max = 16, message = "变更类型不合法") String type,
        /** 目标学号/工号 */
        @NotBlank(message = "目标学号/工号不能为空")
        @Size(max = 32, message = "目标学号/工号不能超过 32 字符") String targetUserNo,
        /** 目标学院 id */
        @NotNull(message = "目标学院不能为空") Long targetCollegeId,
        /** 目标班级 id（仅 student 需要；teacher 传了即 400） */
        Long targetClassId,
        /**
         * 异动类型（BE-7a，D7 默认必填）：MAJOR_TRANSFER/GRADE_REPEAT/UPGRADE/OTHER，兼容中文。
         * 缺省归一为 OTHER（兼容尚未升级的旧客户端，不阻断历史调用）。
         */
        String changeType) {
}
