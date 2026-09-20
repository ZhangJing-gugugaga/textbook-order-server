package com.tian.textbook.importexport.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 导入批次（import_batch）：上传 → running → done/failed；进度可轮询，错误明细可下载。
 */
@Data
@TableName(value = "import_batch", autoResultMap = true)
public class ImportBatch {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** student/teacher/textbook/teacher_course/change */
    private String bizType;

    private Long semesterId;

    private String fileName;

    private String filePath;

    private Integer total;

    private Integer okCount;

    private Integer errorCount;

    private Integer progressPct;

    /** running/done/failed */
    private String status;

    @TableField(value = "error_detail", typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> errorDetail;

    private String errorFilePath;

    /** 异动批次号（与 change_request.batch_no 对应） */
    private String batchNo;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private Long deleted;
}
