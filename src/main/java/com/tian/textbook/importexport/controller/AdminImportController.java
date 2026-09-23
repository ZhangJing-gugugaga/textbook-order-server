package com.tian.textbook.importexport.controller;

import com.tian.textbook.common.ApiResponse;
import com.tian.textbook.common.annotation.AuditLog;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.importexport.ImportService;
import com.tian.textbook.importexport.dto.BatchStartResponse;
import com.tian.textbook.importexport.dto.ImportPreviewResponse;
import com.tian.textbook.importexport.support.DownloadSupport;
import com.tian.textbook.importexport.template.TemplateService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 管理端导入端点（SPEC §11.3 契约基线）：教材导入 / 任课导入 / 名单导入 + 模板下载。
 *
 * <p>semesterId 缺省 = 当前 active 学期（ImportService 解析，SPEC §5.4 在途快照）；
 * 名单导入按 query role=student|teacher 区分权限码（people:student:import / people:teacher:import）。</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminImportController {

    private final ImportService importService;
    private final TemplateService templateService;

    // ============ 教材库导入（textbook:book:import） ============

    @AuditLog(action = "IMPORT", resource = "import_batch")
    @PostMapping("/textbook/import")
    @PreAuthorize("hasAuthority('textbook:book:import')")
    public ApiResponse<BatchStartResponse> importTextbook(@RequestParam("file") MultipartFile file) {
        Long batchId = importService.startImport("textbook", null, file);
        return ApiResponse.ok(new BatchStartResponse(batchId));
    }

    @GetMapping("/textbook/template")
    @PreAuthorize("hasAuthority('textbook:book:import')")
    public void textbookTemplate(HttpServletResponse response) throws IOException {
        DownloadSupport.attachXlsx(response, "教材库导入模板.xlsx");
        templateService.writeTextbook(response.getOutputStream());
    }

    // ============ 课程任课导入（course:teacher:manage） ============

    @AuditLog(action = "IMPORT", resource = "import_batch")
    @PostMapping("/teacher-course/import")
    @PreAuthorize("hasAuthority('course:teacher:manage')")
    public ApiResponse<BatchStartResponse> importTeacherCourse(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Long semesterId) {
        Long batchId = importService.startImport("teacher_course", semesterId, file);
        return ApiResponse.ok(new BatchStartResponse(batchId));
    }

    @GetMapping("/teacher-course/template")
    @PreAuthorize("hasAuthority('course:teacher:manage')")
    public void teacherCourseTemplate(HttpServletResponse response) throws IOException {
        DownloadSupport.attachXlsx(response, "课程任课导入模板.xlsx");
        templateService.writeTeacherCourse(response.getOutputStream());
    }

    // ============ 名单导入（people:student:import / people:teacher:import） ============

    /**
     * 名单导入（学生/教师）。
     *
     * <p>{@code confirmClassSizeShrink}（B13 局部名单防护）：学生名单会按文件内人数重算班级人数
     * （= 教师填报数量上限），疑似局部名单（下调比例超阈值且不少于下限人数）时必须显式传 true，
     * 否则 409 且 message 回显逐班 diff；先调 {@code POST /api/admin/user/import/preview} 可预览。</p>
     */
    @AuditLog(action = "IMPORT", resource = "import_batch")
    @PostMapping("/user/import")
    @PreAuthorize("(#role == 'student' and hasAuthority('people:student:import')) "
            + "or (#role == 'teacher' and hasAuthority('people:teacher:import'))")
    public ApiResponse<BatchStartResponse> importUsers(
            @RequestParam String role,
            @RequestParam(required = false) Long semesterId,
            @RequestParam(required = false, defaultValue = "false") boolean confirmClassSizeShrink,
            @RequestParam("file") MultipartFile file) {
        Long batchId = importService.startImport(role, semesterId, file, confirmClassSizeShrink);
        return ApiResponse.ok(new BatchStartResponse(batchId));
    }

    /**
     * 名单导入预览（只读，B13）：不落库、不建批次，返回「班级人数 diff / 将新建账号数 /
     * 将停用账号数 / 错误行样例」——管理员据此确认这份名单是全量还是局部，再决定是否导入。
     */
    @PostMapping("/user/import/preview")
    @PreAuthorize("(#role == 'student' and hasAuthority('people:student:import')) "
            + "or (#role == 'teacher' and hasAuthority('people:teacher:import'))")
    public ApiResponse<ImportPreviewResponse> previewUsers(
            @RequestParam String role,
            @RequestParam(required = false) Long semesterId,
            @RequestParam("file") MultipartFile file) {
        return ApiResponse.ok(importService.previewImport(role, semesterId, file));
    }

    @GetMapping("/user/import/template")
    @PreAuthorize("(#role == 'student' and hasAuthority('people:student:import')) "
            + "or (#role == 'teacher' and hasAuthority('people:teacher:import'))")
    public void userImportTemplate(@RequestParam String role, HttpServletResponse response) throws IOException {
        if ("student".equals(role)) {
            DownloadSupport.attachXlsx(response, "学生名单导入模板.xlsx");
            templateService.writeStudent(response.getOutputStream());
        } else if ("teacher".equals(role)) {
            DownloadSupport.attachXlsx(response, "教师名单导入模板.xlsx");
            templateService.writeTeacher(response.getOutputStream());
        } else {
            throw new BizException(ErrorCode.PARAM_INVALID, "role 必须为 student 或 teacher");
        }
    }
}
