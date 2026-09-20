package com.tian.textbook.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 行政班（school_class，组织三表之三；无教学班，需求 14）。
 * student_count = 班级人数，教师征订数量上限来源（W2）。
 */
@Data
@TableName("school_class")
public class SchoolClass {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long majorId;

    private String name;

    private String grade;

    private String fullName;

    private Integer studentCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private Long deleted;
}
