package com.tian.textbook.order.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.order.dto.OrderFormDetailVO;
import com.tian.textbook.order.dto.OrderFormListItem;
import com.tian.textbook.order.dto.OrderFormReviewRequest;
import com.tian.textbook.order.dto.StudentOrderListItem;
import com.tian.textbook.order.service.StudentOrderService;
import com.tian.textbook.order.service.TeacherOrderService;
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

/**
 * 超管征订复核与学生选购查询接口（SPEC §11.4 契约基线，/api/admin/**）。
 *
 * <p>复核工作台（全院表单）+ 内容审核（两级审查第二级）+ 全院选购分页。</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminOrderController {

    private final TeacherOrderService teacherOrderService;
    private final StudentOrderService studentOrderService;

    /** 全院表单分页（复核工作台；semesterId 可空 = 全学期） */
    @GetMapping("/order-forms")
    @PreAuthorize("hasAuthority('order:form:view:all')")
    public ApiResponse<PageResponse<OrderFormListItem>> orderForms(
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false) Long collegeId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String teacherName,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(teacherOrderService.allFormsPage(
                semesterId, collegeId, status, teacherName, page, size));
    }

    /** 征订单详情（含 field_check_result + 明细；Service 层归属校验，越权 403 + 审计） */
    @GetMapping("/order-forms/{id}")
    @PreAuthorize("hasAuthority('order:form:view:all')")
    public ApiResponse<OrderFormDetailVO> orderFormDetail(@PathVariable Long id) {
        return ApiResponse.ok(teacherOrderService.getFormDetail(id));
    }

    /** 内容审核：pass → reviewed；reject → rejected（理由必填 1-200 字 + 补正截止，W4） */
    @PostMapping("/order-forms/{id}/review")
    @PreAuthorize("hasAuthority('order:form:review')")
    public ApiResponse<OrderFormDetailVO> review(@PathVariable Long id,
                                                 @Valid @RequestBody OrderFormReviewRequest request) {
        return ApiResponse.ok(teacherOrderService.review(id, request));
    }

    /** 全院选购分页（semesterId/collegeId/classId/studentName 过滤） */
    @GetMapping("/student-orders")
    @PreAuthorize("hasAuthority('student:order:view:all')")
    public ApiResponse<PageResponse<StudentOrderListItem>> studentOrders(
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false) Long collegeId,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) String studentName,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(studentOrderService.allPage(
                semesterId, collegeId, classId, studentName, page, size));
    }
}
