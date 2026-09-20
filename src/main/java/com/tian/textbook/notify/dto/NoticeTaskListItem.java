package com.tian.textbook.notify.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知任务列表项（GET /api/admin/notice/tasks，SPEC §11.5）。
 *
 * <p>roundLimit/intervalHours 为创建时快照（仅展示与追溯），执行依据 =
 * system_config 当前值（W8，由 progress 接口的 roundLimit 字段体现）。</p>
 */
@Data
public class NoticeTaskListItem {

    private Long id;

    private Long semesterId;

    private String title;

    private String content;

    /** 逗号分隔角色码 */
    private String targetRoles;

    private Integer roundLimit;

    private Integer intervalHours;

    /** manual/system_window_change */
    private String source;

    /** active/closed */
    private String status;

    private LocalDateTime createdAt;

    private Long closedBy;

    private LocalDateTime closedAt;
}
