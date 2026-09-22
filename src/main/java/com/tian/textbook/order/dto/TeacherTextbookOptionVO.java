package com.tian.textbook.order.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 教师填报可选教材 VO（GET /api/teacher/textbook，教师选书器）。
 *
 * <p>仅返回在库教材（status=1），与字段审查 BOOK_ACTIVE 规则一致——停用教材不可选，
 * 提前过滤避免教师选到必被驳回的教材。字段白名单：不含审计列（created_by/deleted 等）。</p>
 */
@Data
public class TeacherTextbookOptionVO {

    private Long textbookId;

    private String isbn;

    private String title;

    private String edition;

    private String author;

    private String press;

    private BigDecimal price;
}
