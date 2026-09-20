package com.tian.textbook.notify.dto;

import lombok.Data;

/**
 * 通知任务进度（GET /api/admin/notice/tasks/{id}/progress，SPEC §11.5）。
 *
 * <p>sent/unauthorized/failed = 各发送状态历史尝试去重人数（含后续已确认的用户，
 * 如实反映触达，W5/R10 不做假「已送达」）；confirmed = 已确认人数
 * （notice_record 确认行 send_status='confirmed'，首次生效）。</p>
 *
 * <p>roundLimit = system_config.notice.round_limit 当前值（唯一真源，W8）。</p>
 */
@Data
public class NoticeProgressResponse {

    private long sent;

    private long unauthorized;

    private long failed;

    private long confirmed;

    private int roundLimit;
}
