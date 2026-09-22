package com.tian.textbook.order.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.annotation.WithinWindow;
import com.tian.textbook.order.dto.OrderFormDetailVO;
import com.tian.textbook.order.dto.OrderFormListItem;
import com.tian.textbook.order.dto.OrderFormSubmitRequest;
import com.tian.textbook.order.dto.TeacherCourseGroupVO;
import com.tian.textbook.order.dto.TeacherTextbookOptionVO;
import com.tian.textbook.order.service.TeacherOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 教师征订接口（SPEC §11.4 契约基线，/api/teacher/**）。
 *
 * <p>提交端点挂 @WithinWindow(exemption=CORRECTION)：窗口 closed 时仅放行被驳回表单的补正重提
 * （W4，仅限本人该表单、状态 ∈ {rejected, rejected_auto} 且未过 correct_deadline）。</p>
 */
@RestController
@RequestMapping("/api/teacher")
@RequiredArgsConstructor
public class TeacherOrderController {

    private final TeacherOrderService teacherOrderService;

    /** 本学期任课关系（按班级分组；征订范围 = 已导入的 teacher_course，W17） */
    @GetMapping("/my-courses")
    @PreAuthorize("hasAuthority('order:form:submit')")
    public ApiResponse<List<TeacherCourseGroupVO>> myCourses() {
        return ApiResponse.ok(teacherOrderService.myCourses());
    }

    /** 当前学期征订单 + 明细（无单返回 data=null） */
    @GetMapping("/order-form")
    @PreAuthorize("hasAuthority('order:form:submit')")
    public ApiResponse<OrderFormDetailVO> myForm() {
        return ApiResponse.ok(teacherOrderService.getMyForm());
    }

    /** 提交/补正（返回字段审查结果；任一不过 → rejected_auto + 400 逐字段回显） */
    @PostMapping("/order-form/submit")
    @PreAuthorize("hasAuthority('order:form:submit')")
    @WithinWindow(exemption = WithinWindow.Exemption.CORRECTION)
    public ApiResponse<OrderFormDetailVO> submit(@Valid @RequestBody OrderFormSubmitRequest request) {
        return ApiResponse.ok(teacherOrderService.submit(request));
    }

    /** 本人历史提交记录（跨学期，带学期名） */
    @GetMapping("/order-forms")
    @PreAuthorize("hasAuthority('order:form:view:self')")
    public ApiResponse<List<OrderFormListItem>> history() {
        return ApiResponse.ok(teacherOrderService.myHistory());
    }

    /** 填报选书器：在库教材检索（title/isbn/author/press 模糊匹配；只读、最小权限） */
    @GetMapping("/textbook")
    @PreAuthorize("hasAuthority('order:form:submit')")
    public ApiResponse<List<TeacherTextbookOptionVO>> textbooks(
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(teacherOrderService.searchTextbooks(keyword));
    }
}
