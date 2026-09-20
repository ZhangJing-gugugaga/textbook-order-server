package com.tian.textbook.order.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.order.dto.OrderFormListItem;
import com.tian.textbook.order.service.TeacherOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 秘书征订查询接口（SPEC §11.4 契约基线，/api/secretary/**）。
 *
 * <p>本院范围：collegeId 从当前用户在 active 学期的归属取（user_semester_profile 真源，W6），
 * 无归属返回空页（绝不越院）。</p>
 */
@RestController
@RequestMapping("/api/secretary")
@RequiredArgsConstructor
public class SecretaryOrderController {

    private final TeacherOrderService teacherOrderService;

    /** 本院表单分页（status/teacherName 过滤；page 从 1 开始，size 上限 200） */
    @GetMapping("/order-forms")
    @PreAuthorize("hasAuthority('order:form:view:college')")
    public ApiResponse<PageResponse<OrderFormListItem>> orderForms(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String teacherName,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(teacherOrderService.collegeFormsPage(status, teacherName, page, size));
    }
}
