package com.tian.textbook.order.dto;

/**
 * 教师征订明细行（课程×班级×教材×数量，SPEC §8）。
 *
 * <p>字段级必填/范围由 {@link com.tian.textbook.order.service.FieldCheckService} 逐字段审查
 * （契约冻结回显 [{field,rule,message}]），故不做静态 jakarta 校验注解，避免抢跑字段审查格式。</p>
 */
public record OrderFormSubmitItem(
        Long courseId,
        Long classId,
        Long textbookId,
        Integer quantity) {
}
