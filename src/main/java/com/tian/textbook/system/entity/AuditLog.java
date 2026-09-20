package com.tian.textbook.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 审计日志（audit_log，只写不改；不含密码/token）。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String userNo;

    /** LOGIN/EXPORT/ACCOUNT/WINDOW/SEMESTER_SWITCH/REVIEW/CHANGE/CONFIG… */
    private String action;

    private String resource;

    private String resourceId;

    @TableField(value = "detail_json", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> detailJson;

    private String ip;

    private LocalDateTime at;
}
