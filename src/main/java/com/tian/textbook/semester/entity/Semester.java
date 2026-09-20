package com.tian.textbook.semester.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 学期（semester，含窗口引擎字段，W11：window_status 落库为唯一真源）。
 */
@Data
@TableName("semester")
public class Semester {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private LocalDate startDate;

    private LocalDate endDate;

    private LocalDateTime windowStart;

    private LocalDateTime windowEnd;

    /** 窗口总开关 */
    private Integer channelOpen;

    /** 到点自动开启 */
    private Integer autoOpen;

    /** 到点自动截止 */
    private Integer autoClose;

    /** not_open/open/closed：落库唯一真源 */
    private String windowStatus;

    /** draft/active/archived */
    private String activeStatus;

    /** 乐观锁（切换/窗口变更携带预期版本号） */
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private Long deleted;
}
