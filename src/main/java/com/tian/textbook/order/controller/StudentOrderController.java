package com.tian.textbook.order.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.annotation.WithinWindow;
import com.tian.textbook.order.dto.StudentBookVO;
import com.tian.textbook.order.dto.StudentOrderDetailVO;
import com.tian.textbook.order.dto.StudentOrderListItem;
import com.tian.textbook.order.dto.StudentOrderSubmitRequest;
import com.tian.textbook.order.service.StudentOrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 学生选购接口（SPEC §11.4 契约基线，/api/student/**）。
 *
 * <p>提交端点挂 @WithinWindow（无豁免）：窗口 closed → 409「本期征订已截止」（R8）。
 * 清单规则（W3）由服务端唯一实现，前端只渲染。</p>
 */
@RestController
@RequestMapping("/api/student")
@RequiredArgsConstructor
public class StudentOrderController {

    private final StudentOrderService studentOrderService;

    /** 本班教材清单（必修标识 required / 是否已下架 delisted，W3） */
    @GetMapping("/book-list")
    @PreAuthorize("hasAuthority('student:order:submit')")
    public ApiResponse<List<StudentBookVO>> bookList() {
        return ApiResponse.ok(studentOrderService.bookList());
    }

    /** 本人选购单 + 明细（无单返回 data=null） */
    @GetMapping("/order")
    @PreAuthorize("hasAuthority('student:order:submit')")
    public ApiResponse<StudentOrderDetailVO> myOrder() {
        return ApiResponse.ok(studentOrderService.getMyOrder());
    }

    /** 提交（覆盖语义；不在清单或已下架 → 400 BOOK_DELISTED） */
    @PostMapping("/order/submit")
    @PreAuthorize("hasAuthority('student:order:submit')")
    @WithinWindow
    public ApiResponse<StudentOrderDetailVO> submit(@Valid @RequestBody StudentOrderSubmitRequest request) {
        return ApiResponse.ok(studentOrderService.submit(request));
    }

    /** 本人历史选购记录 */
    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('student:order:view:self')")
    public ApiResponse<List<StudentOrderListItem>> history() {
        return ApiResponse.ok(studentOrderService.myHistory());
    }
}
