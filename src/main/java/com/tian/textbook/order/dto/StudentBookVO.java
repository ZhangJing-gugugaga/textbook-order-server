package com.tian.textbook.order.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 学生可选教材 VO（GET /api/student/book-list，W3 清单规则）。
 *
 * <p>required = 该教材出现在任一 submitted_at 非空的教师表单中（有教师提交即标必修）；
 * delisted = 出现在教师表单中但没有任何 reviewed 来源（表单后被驳回/仍在审）→ 不可选。</p>
 */
@Data
public class StudentBookVO {

    private Long textbookId;

    private String isbn;

    private String title;

    private String edition;

    private String author;

    private String press;

    private BigDecimal price;

    /** 有教师提交即标必修 */
    private boolean required;

    /** 已下架（无 reviewed 来源）→ 不可选 */
    private boolean delisted;
}
