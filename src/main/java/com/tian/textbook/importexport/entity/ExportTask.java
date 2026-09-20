package com.tian.textbook.importexport.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 导出任务（export_task，Q16/W18）：queued → running → done/failed/expired；
 * 一次性下载 token 单次有效、默认 10 分钟过期，文件保留 24 小时。
 */
@Data
@TableName("export_task")
public class ExportTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** order/signature/student/notice/supplier */
    private String bizType;

    @TableField(value = "params_json", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> paramsJson;

    private Integer rowEstimate;

    private String filePath;

    /** 一次性下载 token（首次下载后置空） */
    private String downloadToken;

    private LocalDateTime tokenExpireAt;

    /** 文件保留截止（24 小时） */
    private LocalDateTime expiresAt;

    private String status;

    private Integer progressPct;

    private String errorMsg;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private Long deleted;
}
