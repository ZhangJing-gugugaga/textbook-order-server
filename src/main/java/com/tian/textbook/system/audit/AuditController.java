package com.tian.textbook.system.audit;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.system.entity.AuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 审计日志查询（W24 / SPEC §11.5：按操作者/动作/资源/时间过滤 + 分页；只读）。
 *
 * <p>分层：Controller 只解析 HTTP 参数，数据查询走 {@link AuditService}（SPEC §2 机检红线：
 * Controller 不得直连 Mapper）。</p>
 */
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AuditController {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AuditService auditService;

    @GetMapping
    @PreAuthorize("hasAuthority('audit:log:view')")
    public ApiResponse<PageResponse<AuditLog>> query(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String userNo,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resource,
            @RequestParam(required = false) String startAt,
            @RequestParam(required = false) String endAt,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        LocalDateTime start = parse(startAt);
        LocalDateTime end = parse(endAt);
        if (start != null && end != null && start.isAfter(end)) {
            throw new com.tian.textbook.common.error.BizException(
                    com.tian.textbook.common.error.ErrorCode.PARAM_INVALID, "时间范围不正确");
        }
        long safeSize = Math.min(Math.max(size, 1), 200);
        long offset = (Math.max(page, 1) - 1) * safeSize;
        List<AuditLog> list = auditService.query(userId, userNo, action, resource, start, end);
        // selectByFilter 返回全量匹配，此处按分页窗口截断（数据量为审计级，可接受）
        int from = (int) Math.min(offset, list.size());
        int to = (int) Math.min(offset + safeSize, list.size());
        return ApiResponse.ok(PageResponse.of(list.subList(from, to), Math.max(page, 1), safeSize, list.size()));
    }

    private LocalDateTime parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value.trim(), FORMATTER);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(value.trim() + " 00:00:00", FORMATTER);
            } catch (Exception ex) {
                throw new com.tian.textbook.common.error.BizException(
                        com.tian.textbook.common.error.ErrorCode.PARAM_INVALID, "时间格式不正确，应为 yyyy-MM-dd HH:mm:ss");
            }
        }
    }
}
