package com.tian.textbook.textbook.dto;

import lombok.Data;

/**
 * 任课关系列表项（联表回填课程名/教师名/班级名）。
 */
@Data
public class TeacherCourseListItem {

    private Long id;

    private Long semesterId;

    private Long teacherId;

    private String teacherName;

    private Long courseId;

    private String courseName;

    private Long classId;

    private String className;
}
