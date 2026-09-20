package com.tian.textbook.semester.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 按学期归属真源（user_semester_profile，W6，双缓冲的关键）：
 * 学生/教师在某学期的学院班级归属；sys_user.college_id/class_id 仅为 active 学期冗余。
 */
@Data
@TableName("user_semester_profile")
public class UserSemesterProfile {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long semesterId;

    private Long collegeId;

    private Long classId;

    /** 该学期是否在册（导入比对结果） */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private Long deleted;
}
