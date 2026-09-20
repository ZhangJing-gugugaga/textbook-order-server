package com.tian.textbook.order.dto;

import lombok.Data;

/**
 * 课程选项（任课关系内的课程 id + 名称）。
 */
@Data
public class CourseOptionVO {

    private Long courseId;

    private String courseName;

    public CourseOptionVO() {
    }

    public CourseOptionVO(Long courseId, String courseName) {
        this.courseId = courseId;
        this.courseName = courseName;
    }
}
