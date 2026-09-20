package com.tian.textbook.order.dto;

/**
 * 学生选购汇总原始行（导出「参考用量」用；分组聚合在 Service 层完成）。
 */
public record StudentOrderSummaryRow(
        Long studentId,
        Long textbookId,
        String isbn,
        String title,
        /** 提交时归属快照 JSON：{collegeId,collegeName,classId,className} */
        String submitSnapshot,
        Integer quantity) {
}
