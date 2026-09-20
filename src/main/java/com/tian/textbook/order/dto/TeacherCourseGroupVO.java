package com.tian.textbook.order.dto;

import lombok.Data;

import java.util.List;

/**
 * 教师任课关系分组（GET /api/teacher/my-courses）：按班级分组，班内课程列表。
 */
@Data
public class TeacherCourseGroupVO {

    private Long classId;

    private String className;

    private List<CourseOptionVO> courses;
}
