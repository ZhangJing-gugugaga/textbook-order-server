package com.tian.textbook.textbook.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.PageResponse;
import com.tian.textbook.textbook.dto.TextbookSaveRequest;
import com.tian.textbook.textbook.dto.TextbookStatusRequest;
import com.tian.textbook.textbook.entity.Textbook;
import com.tian.textbook.textbook.service.TextbookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 教材库维护（SPEC §11.3 契约基线，/api/admin/textbook/**）。
 *
 * <p>导入/模板下载（textbook:book:import）由导入导出模块实现，不在此控制器。</p>
 */
@RestController
@RequestMapping("/api/admin/textbook")
@RequiredArgsConstructor
public class AdminTextbookController {

    private final TextbookService textbookService;

    /** 教材分页检索 */
    @GetMapping
    @PreAuthorize("hasAuthority('textbook:book:manage')")
    public ApiResponse<PageResponse<Textbook>> page(
            @RequestParam(required = false) String isbn,
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String press,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        return ApiResponse.ok(textbookService.page(isbn, title, author, press, status, page, size));
    }

    /** 新增教材（ISBN 查重，status 缺省 1 在库） */
    @PostMapping
    @PreAuthorize("hasAuthority('textbook:book:manage')")
    public ApiResponse<Textbook> create(@Valid @RequestBody TextbookSaveRequest request) {
        return ApiResponse.ok(textbookService.create(request));
    }

    /** 编辑教材（同新增字段，全量提交） */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('textbook:book:manage')")
    public ApiResponse<Textbook> update(@PathVariable Long id, @Valid @RequestBody TextbookSaveRequest request) {
        return ApiResponse.ok(textbookService.update(id, request));
    }

    /** 停用/启用（1 在库 0 停用） */
    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('textbook:book:manage')")
    public ApiResponse<Textbook> updateStatus(@PathVariable Long id, @Valid @RequestBody TextbookStatusRequest request) {
        return ApiResponse.ok(textbookService.updateStatus(id, request.status()));
    }
}
