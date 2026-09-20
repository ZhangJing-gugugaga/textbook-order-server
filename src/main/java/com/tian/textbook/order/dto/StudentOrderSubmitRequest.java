package com.tian.textbook.order.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 学生选购提交请求（POST /api/student/order/submit）。
 *
 * <p>覆盖语义：重提 = 整单覆盖（SPEC §12）；空列表 = 清空已选（仍记一次 submitted）。</p>
 */
public record StudentOrderSubmitRequest(

        @NotNull(message = "选购清单不能为空")
        List<StudentOrderSubmitItem> items) {
}
