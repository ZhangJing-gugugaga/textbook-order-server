package com.tian.textbook.notify.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 我的通知项（GET /api/notice/mine，「我的」页数据源）。
 *
 * <p>与 {@link UnconfirmedNoticeItem} 的差异：含全部状态（active/closed）与已确认记录，
 * 并回显 {@code confirmedAt}（未确认为 null）；不含 roundStopped（历史列表不涉及重发轮次）。</p>
 */
@Data
public class MyNoticeItem {

    private Long taskId;

    private String title;

    private String content;

    /** manual/system_window_change */
    private String source;

    /** active/closed */
    private String status;

    private LocalDateTime createdAt;

    /** 本人确认时间；未确认为 null（前端据此判断「待确认/已确认」） */
    private LocalDateTime confirmedAt;
}
