package com.tian.textbook.system.audit;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.util.TimeFormats;
import com.tian.textbook.system.entity.AuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

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
        LocalDateTime start = parseStart(startAt);
        LocalDateTime end = parseEnd(endAt);
        if (start != null && end != null && start.isAfter(end)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "时间范围不正确");
        }
        // 分页下推到 SQL（审计表只增不减，全量取出再内存截断会形成内存尖峰）
        return ApiResponse.ok(auditService.query(userId, userNo, action, resource, start, end, page, size));
    }

    /**
     * 时间范围下界：纯日期视为当日 00:00:00；其余按 {@link TimeFormats} 宽容解析
     * （ISO-8601 与 {@code yyyy-MM-dd HH:mm:ss} 都接受——此前只认空格格式，前端传 ISO 直接 400）。
     */
    private LocalDateTime parseStart(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (TimeFormats.isDateOnly(value)) {
            return TimeFormats.startOfDay(value);
        }
        return parse(value);
    }

    /** 时间范围上界：纯日期视为当日**结束**（23:59:59.999999999）。 */
    private LocalDateTime parseEnd(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (TimeFormats.isDateOnly(value)) {
            return TimeFormats.endOfDay(value);
        }
        return parse(value);
    }

    private LocalDateTime parse(String value) {
        try {
            return TimeFormats.parseInput(value);
        } catch (DateTimeParseException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, TimeFormats.INPUT_HINT);
        }
    }
}
