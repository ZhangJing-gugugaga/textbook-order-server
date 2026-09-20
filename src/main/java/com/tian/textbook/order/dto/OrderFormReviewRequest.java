package com.tian.textbook.order.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 征订单内容审核请求（POST /api/admin/order-forms/{id}/review）。
 *
 * <p>action ∈ {pass, reject}；reject 时 reason 必填 1-200 字（Service 层按结论条件校验，
 * 错误文案统一为「请填写驳回理由」，PRD 模块 5）。</p>
 */
public record OrderFormReviewRequest(

        @NotBlank(message = "审核结论不能为空")
        String action,

        String reason) {
}
