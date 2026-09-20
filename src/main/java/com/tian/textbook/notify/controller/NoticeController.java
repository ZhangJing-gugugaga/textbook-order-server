package com.tian.textbook.notify.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.notify.dto.NoticeConfirmRequest;
import com.tian.textbook.notify.dto.UnconfirmedNoticeItem;
import com.tian.textbook.notify.service.NotifyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通知确认闭环 · 用户侧（SPEC §11.5：/api/notice/**，仅需登录）。
 *
 * <p>弹窗阻塞数据源：GET /unconfirmed；确认：POST /{taskId}/confirm（幂等，204 无 body）。</p>
 */
@RestController
@RequestMapping("/api/notice")
@RequiredArgsConstructor
public class NoticeController {

    private final NotifyService notifyService;

    /** 未确认任务队列（含已达 round_limit 停止重发但未确认的任务，Q7） */
    @GetMapping("/unconfirmed")
    public ApiResponse<List<UnconfirmedNoticeItem>> unconfirmed() {
        return ApiResponse.ok(notifyService.listUnconfirmed());
    }

    /** 确认（幂等：重复调用仍 204；body 可选 subscribeResult=accepted/rejected） */
    @PostMapping("/{taskId}/confirm")
    public ResponseEntity<Void> confirm(@PathVariable Long taskId,
                                        @RequestBody(required = false) @Valid NoticeConfirmRequest request) {
        notifyService.confirm(taskId, request);
        return ResponseEntity.noContent().build();
    }
}
