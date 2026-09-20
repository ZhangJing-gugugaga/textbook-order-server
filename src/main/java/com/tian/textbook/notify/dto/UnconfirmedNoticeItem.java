package com.tian.textbook.notify.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 未确认通知项（GET /api/notice/unconfirmed，弹窗阻塞数据源，SPEC §9/§11.5）。
 *
 * <p>含已达 round_limit 停止订阅重发但未确认的任务（Q7：弹窗通道不设轮次上限，
 * 对已停止重发的任务仍返回，roundStopped=true 标识）。</p>
 */
@Data
public class UnconfirmedNoticeItem {

    private Long taskId;

    private String title;

    private String content;

    /** manual/system_window_change */
    private String source;

    private LocalDateTime createdAt;

    /** 已达 round_limit 停止订阅消息重发（弹窗仍展示，Q7） */
    private boolean roundStopped;
}
