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
import org.springframework.web.bind.annotation.PathVariable;
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

    /**
     * 本人征订单详情（BE-3：修线上 403）。
     *
     * <p>此前前端「我的提交记录 → 明细」调的是 {@code GET /api/admin/order-forms/{id}}，
     * 该端点要求 {@code order:form:view:all}（教师不持有）→ 必然 403。本端点用教师自己的
     * 归属权限码 {@code order:form:view:self}，归属校验由 Service 的 {@code canAccessForm}
     * 完成（非本人 403 + 审计、不存在 404）。</p>
     */
    @GetMapping("/order-forms/{id}")
    @PreAuthorize("hasAuthority('order:form:view:self')")
    public ApiResponse<OrderFormDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.ok(teacherOrderService.getFormDetail(id));
    }

    /**
     * 主动撤回（BE-4）：下一流程（管理员审核）完成前，教师可把 pending_review 撤回为 draft。
     *
     * <p>窗口非 open → 409 WINDOW_CLOSED（无补正豁免：撤回是修改的前提，窗口关了不该还能改）；
     * 归属校验在 Service（非本人 403 + 审计）；与审核并发由行锁串行化——撤回后审核 CAS 落空 409，
     * 审批结论不会被静默撤销。</p>
     */
    @PostMapping("/order-form/withdraw")
    @PreAuthorize("hasAuthority('order:form:submit')")
    @WithinWindow
    public ApiResponse<OrderFormDetailVO> withdraw() {
        return ApiResponse.ok(teacherOrderService.withdraw());
    }

    /** 填报选书器：在库教材检索（title/isbn/author/press 模糊匹配；只读、最小权限） */
    @GetMapping("/textbook")
    @PreAuthorize("hasAuthority('order:form:submit')")
    public ApiResponse<List<TeacherTextbookOptionVO>> textbooks(
            @RequestParam(required = false) String keyword) {
        return ApiResponse.ok(teacherOrderService.searchTextbooks(keyword));
    }
}
