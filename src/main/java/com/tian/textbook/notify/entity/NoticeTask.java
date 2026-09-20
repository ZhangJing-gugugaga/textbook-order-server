package com.tian.textbook.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知任务（notice_task）：手动 + 窗口变更自动（合并进同一 active 任务，W18）；
 * 同学期最多 1 个 active 手动任务。round_limit/interval_hours 为创建时快照，
 * 执行依据 = system_config（W8）。
 */
@Data
@TableName("notice_task")
public class NoticeTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long semesterId;

    private String title;

    private String content;

    /** 逗号分隔角色码，窗口变更通知范围 = 秘书+教师+学生（G4） */
    private String targetRoles;

    private Integer roundLimit;

    private Integer intervalHours;

    /** manual/system_window_change */
    private String source;

    /** active/closed */
    private String status;

    private Long closedBy;

    private LocalDateTime closedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private Long deleted;
}
