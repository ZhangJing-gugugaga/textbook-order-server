package com.tian.textbook.textbook.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任课关系（teacher_course，学期域；征订范围即此表，W17）。
 */
@Data
@TableName("teacher_course")
public class TeacherCourse {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long semesterId;

    /** 教师用户 id */
    private Long teacherId;

    private Long courseId;

    private Long classId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private Long deleted;
}
