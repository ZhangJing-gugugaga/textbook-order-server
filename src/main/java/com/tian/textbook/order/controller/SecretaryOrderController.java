package com.tian.textbook.order.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.order.dto.OrderFormDetailVO;
import com.tian.textbook.order.dto.OrderFormListItem;
import com.tian.textbook.order.service.TeacherOrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

    /**
     * 本院征订单详情（BE-3：修线上 403）。
     *
     * <p>此前前端「本院征订记录 → 查看明细」调的是 {@code GET /api/admin/order-forms/{id}}
     * （要求 {@code order:form:view:all}，秘书不持有）→ 必然 403。本端点用秘书自己的归属权限码
     * {@code order:form:view:college}；范围校验由 Service 的 {@code canAccessForm} 完成
     * （本院取自 {@code user_semester_profile}，无归属即 403，绝不越院）。</p>
     */
    @GetMapping("/order-forms/{id}")
    @PreAuthorize("hasAuthority('order:form:view:college')")
    public ApiResponse<OrderFormDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(teacherOrderService.getFormDetail(id));
    }
}
