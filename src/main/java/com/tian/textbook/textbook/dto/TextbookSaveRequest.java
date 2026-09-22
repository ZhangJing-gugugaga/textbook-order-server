package com.tian.textbook.textbook.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 教材新增/编辑请求（SPEC §11.3：POST/PUT /api/admin/textbook）。
 *
 * <p>编辑与新增同字段（全量提交语义）；status 缺省按 1（在库）处理。</p>
 *
 * <p>@Size 与 DDL 列宽一致（isbn VARCHAR(20) / title VARCHAR(200) / edition VARCHAR(64) /
 * author VARCHAR(128) / press VARCHAR(128)）：缺少上限时超长输入会触发 DB 1406
 * （Data too long）并被兜底成 500，而非可读的 400。</p>
 */
public record TextbookSaveRequest(
        @NotBlank(message = "ISBN 不能为空") @Size(max = 20, message = "ISBN 不能超过 20 字符") String isbn,
        @NotBlank(message = "书名不能为空") @Size(max = 200, message = "书名不能超过 200 字符") String title,
        @Size(max = 64, message = "版次不能超过 64 字符") String edition,
        @Size(max = 128, message = "作者不能超过 128 字符") String author,
        @Size(max = 128, message = "出版社不能超过 128 字符") String press,
        @DecimalMin(value = "0", message = "价格不能为负") BigDecimal price,
        Integer status) {
}
