package com.tian.textbook.notify.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.notify.dto.MyNoticeItem;
import com.tian.textbook.notify.dto.NoticeConfirmRequest;
import com.tian.textbook.notify.dto.UnconfirmedNoticeItem;
import com.tian.textbook.notify.service.NotifyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通知确认闭环 · 用户侧（SPEC §11.5：/api/notice/**，仅需登录）。
 *
 * <p>弹窗阻塞数据源：GET /unconfirmed；确认：POST /{taskId}/confirm（幂等，204 无 body）。</p>
 *
 * <p>可见性由 {@code notice_task.target_roles} 在 Service 层裁剪（本模块无独立权限码：
 * 三类端点都是「本人可见通知」语义，定向由 target_roles 表达；供货商不在窗口变更通知的
 * target_roles 内，因此读不到内部通知正文，也无法确认）。</p>
 */
@RestController
@RequestMapping("/api/notice")
@RequiredArgsConstructor
public class NoticeController {

    private final NotifyService notifyService;

    /** 未确认任务队列（含已达 round_limit 停止重发但未确认的任务，Q7） */
    @GetMapping("/unconfirmed")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<UnconfirmedNoticeItem>> unconfirmed() {
        return ApiResponse.ok(notifyService.listUnconfirmed());
    }

    /**
     * 我的通知（分页）：当前学期面向本人的通知任务，含已确认与已关闭（「我的」页数据源）。
     *
     * <p>与 /unconfirmed 同口径（按 target_roles 过滤），差异是含已确认记录并回显
     * confirmedAt，供「我的」页展示全量历史。</p>
     */
    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<PageResponse<MyNoticeItem>> mine(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(notifyService.myNotices(page, size));
    }

    /** 确认（幂等：重复调用仍 204；body 可选 subscribeResult=accepted/rejected） */
    @PostMapping("/{taskId}/confirm")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Void> confirm(@PathVariable Long taskId,
                                        @RequestBody(required = false) @Valid NoticeConfirmRequest request) {
        notifyService.confirm(taskId, request);
        return ResponseEntity.noContent().build();
    }
}
