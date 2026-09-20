package com.tian.textbook.textbook.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

/**
 * 教材新增/编辑请求（SPEC §11.3：POST/PUT /api/admin/textbook）。
 *
 * <p>编辑与新增同字段（全量提交语义）；status 缺省按 1（在库）处理。</p>
 */
public record TextbookSaveRequest(
        @NotBlank(message = "ISBN 不能为空") String isbn,
        @NotBlank(message = "书名不能为空") String title,
        String edition,
        String author,
        String press,
        @DecimalMin(value = "0", message = "价格不能为负") BigDecimal price,
        Integer status) {
}
