package com.tian.textbook.order.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 征订单内容审核请求（POST /api/admin/order-forms/{id}/review）。
 *
 * <p>action ∈ {pass, reject}；reject 时 reason 必填 1-200 字（Service 层按结论条件校验，
 * 错误文案统一为「请填写驳回理由」，PRD 模块 5）。</p>
 *
 * @param contentVersion 审核页读到的内容版本（来自详情接口的 {@code contentVersion}）。
 *                       服务端以此作为 CAS 谓词，识别「打开详情后教师又重提过」。
 *                       传 null 时退化为「与本次请求内读到的版本比对」——只能拦住
 *                       请求处理窗口内的并发提交，<b>拦不住跨请求的重提</b>，
 *                       故前端应始终回传。详见 {@link OrderFormDetailVO#getContentVersion()}。
 */
public record OrderFormReviewRequest(

        @NotBlank(message = "审核结论不能为空")
        String action,

        String reason,

        Integer contentVersion) {

    /** 兼容旧调用（不传版本号，降级为请求内比对）。 */
    public OrderFormReviewRequest(String action, String reason) {
        this(action, reason, null);
    }
}
