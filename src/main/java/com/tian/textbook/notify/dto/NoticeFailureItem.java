package com.tian.textbook.notify.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 未授权/失败名单项（GET /api/admin/notice/tasks/{id}/failures，线下兜底，W5/R10）。
 *
 * <p>最后一轮发送状态为 unauthorized/failed 且未确认的用户（汇总导出的兜底列数据源）。</p>
 */
@Data
public class NoticeFailureItem {

    private Long userId;

    private String userNo;

    private String name;

    private String role;

    private Long collegeId;

    private String collegeName;

    private Long classId;

    private String className;

    /** unauthorized/failed */
    private String sendStatus;

    private Integer roundNo;

    private LocalDateTime sentAt;
}
