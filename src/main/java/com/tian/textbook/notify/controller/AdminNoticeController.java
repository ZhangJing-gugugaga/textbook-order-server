package com.tian.textbook.notify.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.notify.dto.NoticeFailureItem;
import com.tian.textbook.notify.dto.NoticeProgressResponse;
import com.tian.textbook.notify.dto.NoticeTaskCreateRequest;
import com.tian.textbook.notify.dto.NoticeTaskListItem;
import com.tian.textbook.notify.service.NotifyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通知任务管理 · 超管侧（SPEC §11.5：/api/admin/notice/**）。
 *
 * <p>同学期最多 1 个 active 手动任务（W18）；进度与失败名单供教材室追踪触达
 * （unauthorized 进线下兜底名单，W5/R10）。</p>
 */
@RestController
@RequestMapping("/api/admin/notice")
@RequiredArgsConstructor
public class AdminNoticeController {

    private final NotifyService notifyService;

    /** active 学期任务列表（含 closed 历史，id DESC） */
    @GetMapping("/tasks")
    @PreAuthorize("hasAuthority('notice:task:view')")
    public ApiResponse<List<NoticeTaskListItem>> tasks() {
        return ApiResponse.ok(notifyService.listTasks());
    }

    /** 手动创建（同学期已有 active 任务 → 409 NOTICE_TASK_EXISTS） */
    @PostMapping("/tasks")
    @PreAuthorize("hasAuthority('notice:task:manage')")
    public ApiResponse<NoticeTaskListItem> create(@Valid @RequestBody NoticeTaskCreateRequest request) {
        return ApiResponse.ok(notifyService.createTask(request));
    }

    /** 手动关闭（status=closed + closed_by/closed_at + 审计） */
    @PostMapping("/tasks/{id}/close")
    @PreAuthorize("hasAuthority('notice:task:manage')")
    public ApiResponse<NoticeTaskListItem> close(@PathVariable Long id) {
        return ApiResponse.ok(notifyService.closeTask(id));
    }

    /** 发送/确认进度（roundLimit = system_config 当前值，W8） */
    @GetMapping("/tasks/{id}/progress")
    @PreAuthorize("hasAuthority('notice:task:view')")
    public ApiResponse<NoticeProgressResponse> progress(@PathVariable Long id) {
        return ApiResponse.ok(notifyService.taskProgress(id));
    }

    /** 未授权/失败名单（分页，线下兜底，W5/R10） */
    @GetMapping("/tasks/{id}/failures")
    @PreAuthorize("hasAuthority('notice:task:view')")
    public ApiResponse<PageResponse<NoticeFailureItem>> failures(@PathVariable Long id,
                                                                 @RequestParam(defaultValue = "1") long page,
                                                                 @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(notifyService.taskFailures(id, page, size));
    }
}
