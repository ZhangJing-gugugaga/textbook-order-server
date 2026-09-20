package com.tian.textbook.approval.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 按批次批量审批请求（SPEC §11.4：POST /api/admin/change/batch/review，Q10）。
 *
 * <p>处理该 batchNo 下全部 pending_review 记录（逐条走单条审批逻辑，同一事务）；
 * reject 时 reason 必填 1-200 字。</p>
 */
public record ChangeBatchReviewRequest(
        /** 批次号（与 change_request.batch_no / import_batch.batch_no 对应） */
        @NotBlank(message = "批次号不能为空") String batchNo,
        /** 审批动作：pass/reject */
        @NotBlank(message = "审批动作不能为空") String action,
        /** 驳回理由（reject 时必填，1-200 字） */
        @Size(max = 200, message = "驳回理由不能超过 200 字") String reason) {
}
