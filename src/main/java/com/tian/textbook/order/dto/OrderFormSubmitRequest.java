package com.tian.textbook.order.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * 教师征订提交请求（POST /api/teacher/order-form/submit）。
 *
 * <p>覆盖语义：重提 = 整单覆盖（先逻辑删旧明细再插新，SPEC §12）；
 * 字段审查全过 → pending_review，任一不过 → rejected_auto + 逐字段错误。</p>
 */
public record OrderFormSubmitRequest(

        @NotNull(message = "征订明细不能为空")
        List<OrderFormSubmitItem> items) {
}
