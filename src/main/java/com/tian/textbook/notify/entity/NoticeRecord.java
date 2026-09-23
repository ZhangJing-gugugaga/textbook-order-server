package com.tian.textbook.notify.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 通知历史（notice_record，主体=用户，覆盖秘书/教师/学生，W7）。
 *
 * <p>每次发送尝试写 (round_no, sent_at, send_status)；确认写 confirmed_at（round_no 为空，
 * 唯一键 uk_notice_round 保证 confirm 幂等、首次生效）。</p>
 *
 * <p>{@code semesterId}（BE-5d）：学期归档时按此列把记录迁到 {@code notice_record_history}，
 * 主表只留未归档学期，避免数据量随学期线性增长。</p>
 */
@Data
@TableName("notice_record")
public class NoticeRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private Long userId;

    /** 所属学期（BE-5d：归档迁移判定列；确认记录同样写入） */
    private Long semesterId;

    /** 第几轮（确认记录为空） */
    private Integer roundNo;

    private LocalDateTime sentAt;

    /** sent/unauthorized/failed：如实记录，不做假「已送达」 */
    private String sendStatus;

    private LocalDateTime confirmedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private Long deleted;
}
