package com.tian.textbook.approval.controller;

import com.tian.textbook.approval.dto.ChangeBatchReviewRequest;
import com.tian.textbook.approval.dto.ChangeBatchReviewResult;
import com.tian.textbook.approval.dto.ChangeRequestListItem;
import com.tian.textbook.approval.dto.ChangeReviewRequest;
import com.tian.textbook.approval.dto.ChangeRequestVO;
import com.tian.textbook.approval.service.ChangeRequestService;
import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.PageResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 异动审批端（SPEC §11.4 契约基线：审批列表、单条审批、按批次批量审批）。
 */
@RestController
@RequestMapping("/api/admin/change")
@RequiredArgsConstructor
public class AdminChangeRequestController {

    private final ChangeRequestService changeRequestService;

    /** 审批列表（semesterId/status/batchNo/type/changeType 过滤，分页；BE-7a 新增 changeType 筛选） */
    @GetMapping
    @PreAuthorize("hasAuthority('change:request:review')")
    public ApiResponse<PageResponse<ChangeRequestListItem>> page(
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String batchNo,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String changeType,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(changeRequestService.page(semesterId, status, batchNo, type, changeType, page, size));
    }

    /** 单条审批（pass/reject，reject 理由必填；仅 pending_review 可审） */
    @PostMapping("/{id}/review")
    @PreAuthorize("hasAuthority('change:request:review')")
    public ApiResponse<ChangeRequestVO> review(@PathVariable Long id,
                                               @Valid @RequestBody ChangeReviewRequest request) {
        return ApiResponse.ok(changeRequestService.review(id, request));
    }

    /** 按批次批量审批（该 batchNo 下全部 pending_review，同一事务） */
    @PostMapping("/batch/review")
    @PreAuthorize("hasAuthority('change:request:review')")
    public ApiResponse<ChangeBatchReviewResult> batchReview(@Valid @RequestBody ChangeBatchReviewRequest request) {
        return ApiResponse.ok(changeRequestService.batchReview(request));
    }
}
