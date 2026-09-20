package com.tian.textbook.notify.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 手动创建通知任务（POST /api/admin/notice/tasks，SPEC §11.5）。
 *
 * <p>同学期仅 1 个 active 任务，重复创建返回 409 NOTICE_TASK_EXISTS（W18）；
 * round_limit/interval_hours 不接收入参，从 system_config 快照（W8）。</p>
 */
public record NoticeTaskCreateRequest(

        @NotBlank(message = "标题不能为空")
        @Size(max = 120, message = "标题不能超过 120 字")
        String title,

        @NotBlank(message = "内容不能为空")
        @Size(max = 500, message = "内容不能超过 500 字")
        String content,

        /** 逗号分隔角色码，为空默认 STUDENT（DDL 默认值） */
        @Size(max = 64, message = "目标角色不能超过 64 字")
        String targetRoles) {

    /** 目标角色兜底：DDL 默认 STUDENT */
    public String targetRolesOrDefault() {
        return targetRoles == null || targetRoles.isBlank() ? "STUDENT" : targetRoles.trim();
    }
}
