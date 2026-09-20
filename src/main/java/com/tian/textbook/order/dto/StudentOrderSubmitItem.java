package com.tian.textbook.order.dto;

/**
 * 学生选购明细行（教材×数量，PRD 模块 5：数量 1-9 且受班级人数约束）。
 *
 * <p>可选购范围（在库 reviewed 教材）与数量上限由 Service 校验，静态注解不表达动态上限。</p>
 */
public record StudentOrderSubmitItem(
        Long textbookId,
        Integer quantity) {
}
