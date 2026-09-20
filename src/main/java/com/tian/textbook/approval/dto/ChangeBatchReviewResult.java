package com.tian.textbook.approval.dto;

import lombok.Data;

import java.util.List;

/**
 * 按批次批量审批结果（SPEC §11.4：POST /api/admin/change/batch/review）。
 */
@Data
public class ChangeBatchReviewResult {

    private String batchNo;

    /** pass/reject */
    private String action;

    /** 本次实际处理（pending_review → 终态）的条数；已处理过的记录跳过 */
    private int count;

    /** 逐条处理结果 */
    private List<ChangeRequestVO> records;
}
