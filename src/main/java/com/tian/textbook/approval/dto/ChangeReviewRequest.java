package com.tian.textbook.approval.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 单条异动审批请求（SPEC §11.4：POST /api/admin/change/{id}/review）。
 *
 * <p>action ∈ {pass, reject}；reject 时 reason 必填 1-200 字（PRD 模块 6）。</p>
 */
public record ChangeReviewRequest(
        /** 审批动作：pass/reject */
        @NotBlank(message = "审批动作不能为空") String action,
        /** 驳回理由（reject 时必填，1-200 字） */
        @Size(max = 200, message = "驳回理由不能超过 200 字") String reason) {
}
