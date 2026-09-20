package com.tian.textbook.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 学生选购单（student_order，一人一学期一单；重提 = 整单覆盖）。
 */
@Data
@TableName("student_order")
public class StudentOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long semesterId;

    /** 学生用户 id */
    private Long studentId;

    /** draft/submitted */
    private String status;

    /** 提交时归属快照 {collegeId,collegeName,classId,className}，异动不影响历史归属（W15） */
    @TableField(value = "submit_snapshot", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> submitSnapshot;

    private LocalDateTime submittedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private Long deleted;
}
