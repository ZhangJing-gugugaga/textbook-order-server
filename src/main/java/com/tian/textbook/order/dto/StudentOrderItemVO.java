package com.tian.textbook.order.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 学生选购明细 VO（教材信息 + delisted 状态：已下架的不可选但保留展示，W3）。
 */
@Data
public class StudentOrderItemVO {

    private Long id;

    private Long textbookId;

    private String isbn;

    private String title;

    private String edition;

    private String author;

    private String press;

    private BigDecimal price;

    /** 已下架（当前无 reviewed 来源）→ 提交时会被剔除 */
    private boolean delisted;

    private Integer quantity;

    /** 教材来源课程（可追溯） */
    private Long courseId;
}
