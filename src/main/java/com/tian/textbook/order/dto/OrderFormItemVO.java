package com.tian.textbook.order.dto;

import lombok.Data;

/**
 * 征订明细 VO（课程×班级×教材×数量，关联名称一次带出，前端不二次拉取）。
 */
@Data
public class OrderFormItemVO {

    private Long id;

    private Long courseId;

    private String courseName;

    private Long classId;

    private String className;

    private Long textbookId;

    private String textbookTitle;

    private String isbn;

    private Integer quantity;
}
