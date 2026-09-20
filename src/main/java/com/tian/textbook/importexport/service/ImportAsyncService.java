package com.tian.textbook.importexport.service;

import com.alibaba.excel.EasyExcel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.tian.textbook.common.config.AsyncConfig;
import com.tian.textbook.common.config.TextbookProperties;
import com.tian.textbook.common.CurrentUser;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.importexport.entity.ImportBatch;
import com.tian.textbook.importexport.excel.ImportErrorRow;
import com.tian.textbook.importexport.excel.StudentImportRow;
import com.tian.textbook.importexport.excel.TeacherCourseImportRow;
import com.tian.textbook.importexport.excel.TeacherImportRow;
import com.tian.textbook.importexport.excel.TextbookImportRow;
import com.tian.textbook.importexport.mapper.ImportBatchMapper;
import com.tian.textbook.importexport.support.ImportReadListener;
import com.tian.textbook.importexport.support.ImportRunContext;
import com.tian.textbook.importexport.support.ImportRunSummary;
import com.tian.textbook.importexport.support.IsbnUtils;
import com.tian.textbook.semester.SemesterActiveService;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.mapper.SemesterMapper;
import com.tian.textbook.semester.mapper.UserSemesterProfileMapper;
import com.tian.textbook.system.audit.AuditService;
import com.tian.textbook.system.config.ConfigService;
import com.tian.textbook.system.entity.College;
import com.tian.textbook.system.entity.Major;
import com.tian.textbook.system.entity.SchoolClass;
import com.tian.textbook.system.entity.SysUser;
import com.tian.textbook.system.mapper.CollegeMapper;
import com.tian.textbook.system.mapper.MajorMapper;
import com.tian.textbook.system.mapper.SchoolClassMapper;
import com.tian.textbook.system.mapper.SysRoleMapper;
import com.tian.textbook.system.mapper.SysUserMapper;
import com.tian.textbook.system.mapper.SysUserRoleMapper;
import com.tian.textbook.textbook.entity.Course;
import com.tian.textbook.textbook.mapper.CourseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异步导入执行器（SPEC §10：@Async("importExecutor") + EasyExcel 流式解析）。
 *
 * <p>职责：按 bizType 装配监听器（行校验 + 每 500 行落库 + 进度 + 错误收集）、
 * 收尾写批次终态（ok/error/error_detail/error_file_path + 停用比对摘要）、
 * 解析异常整体标记 failed。落盘临时文件解析后即删（SPEC §15）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportAsyncService {

    private final ImportBatchMapper importBatchMapper;
    private final ImportRowWriter rowWriter;
    private final TextbookProperties properties;
    private final ConfigService configService;
    private final AuditService auditService;
    private final SemesterActiveService activeSemesterService;
    private final CollegeMapper collegeMapper;
    private final MajorMapper majorMapper;
    private final SchoolClassMapper schoolClassMapper;
    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SemesterMapper semesterMapper;
    private final UserSemesterProfileMapper profileMapper;
    private final CourseMapper courseMapper;

    /**
     * 异步解析（专用线程池 importExecutor；批次数与进度落库，前端轮询 GET /api/batch/{id}）。
     *
     * @param filePath 已落盘的上传文件（解析后删除）
     * @param operator 上传操作者（审计留痕：@Async 线程默认不带 SecurityContext）
     */
    @Async(AsyncConfig.IMPORT_EXECUTOR)
    public void process(String bizType, Long batchId, Long semesterId, String filePath, CurrentUser operator) {
        if (operator != null) {
            // 线程池线程会复用：审计/权限上下文用后必须清理（与 JwtAuthFilter 同一主体构造方式）
            List<GrantedAuthority> authorities = new ArrayList<>();
            if (operator.permissions() != null) {
                for (String permission : operator.permissions()) {
                    authorities.add(new SimpleGrantedAuthority(permission));
                }
            }
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(operator, null, authorities));
        }
        SemesterContextHolder.set(semesterId);
        long startMs = System.currentTimeMillis();
        try {
            File file = new File(filePath);
            if (!file.exists()) {
                throw new BizException(ErrorCode.NOT_FOUND, "上传文件不存在，请重传");
            }
            ImportRunContext ctx = newContext(batchId, bizType, semesterId);
            switch (bizType) {
                case "student" -> EasyExcel.read(file, StudentImportRow.class, listener(ctx,
                        this::validateStudent, rowWriter::writeStudentRows)).sheet().doRead();
                case "teacher" -> EasyExcel.read(file, TeacherImportRow.class, listener(ctx,
                        this::validateTeacher, rowWriter::writeTeacherRows)).sheet().doRead();
                case "textbook" -> EasyExcel.read(file, TextbookImportRow.class, listener(ctx,
                        this::validateTextbook, rowWriter::writeTextbookRows)).sheet().doRead();
                case "teacher_course" -> EasyExcel.read(file, TeacherCourseImportRow.class, listener(ctx,
                        this::validateTeacherCourse, rowWriter::writeTeacherCourseRows)).sheet().doRead();
                default -> throw new BizException(ErrorCode.PARAM_INVALID, "不支持的导入类型: " + bizType);
            }
        } catch (Exception e) {
            log.error("导入批次解析失败: batchId={}, bizType={}", batchId, bizType, e);
            markFailed(batchId, e);
        } finally {
            SemesterContextHolder.clear();
            SecurityContextHolder.clearContext();
            deleteQuietly(filePath);
            log.info("导入批次结束: batchId={}, bizType={}, 耗时={}ms", batchId, bizType,
                    System.currentTimeMillis() - startMs);
        }
    }

    // ============ 行校验（严格模式：不自动创建学院/班级，逐行报错） ============

    private String validateStudent(StudentImportRow row, int excelRow, ImportRunContext ctx) {
        String userNo = trim(row.getUserNo());
        if (userNo.isEmpty()) {
            return "学号不能为空";
        }
        if (trim(row.getName()).isEmpty()) {
            return "姓名不能为空";
        }
        String collegeName = trim(row.getCollegeName());
        if (collegeName.isEmpty()) {
            return "学院不能为空";
        }
        College college = ctx.college(collegeName);
        if (college == null) {
            return "学院不存在";
        }
        String majorName = trim(row.getMajorName());
        if (majorName.isEmpty()) {
            return "专业不能为空";
        }
        Major major = ctx.major(college.getId(), majorName);
        if (major == null) {
            return "专业不存在";
        }
        String className = trim(row.getClassName());
        if (className.isEmpty()) {
            return "班级不能为空";
        }
        SchoolClass clazz = ctx.schoolClass(major.getId(), className);
        if (clazz == null) {
            return "班级不存在";
        }
        String phone = trim(row.getPhone());
        if (!phone.isEmpty() && !phone.matches("\\d{5,20}")) {
            return "手机号格式不正确";
        }
        row.setCollegeId(college.getId());
        row.setClassId(clazz.getId());
        // 停用比对范围（W14）：仅文件内学院 + 角色
        ctx.recordUser(userNo);
        ctx.recordCollege(college.getId());
        ctx.recordClass(clazz.getId());
        return null;
    }

    private String validateTeacher(TeacherImportRow row, int excelRow, ImportRunContext ctx) {
        String userNo = trim(row.getUserNo());
        if (userNo.isEmpty()) {
            return "工号不能为空";
        }
        if (trim(row.getName()).isEmpty()) {
            return "姓名不能为空";
        }
        String collegeName = trim(row.getCollegeName());
        if (collegeName.isEmpty()) {
            return "学院不能为空";
        }
        College college = ctx.college(collegeName);
        if (college == null) {
            return "学院不存在";
        }
        String phone = trim(row.getPhone());
        if (!phone.isEmpty() && !phone.matches("\\d{5,20}")) {
            return "手机号格式不正确";
        }
        row.setCollegeId(college.getId());
        ctx.recordUser(userNo);
        ctx.recordCollege(college.getId());
        return null;
    }

    private String validateTextbook(TextbookImportRow row, int excelRow, ImportRunContext ctx) {
        String isbn = IsbnUtils.normalize(row.getIsbn());
        if (isbn.isEmpty()) {
            return "ISBN不能为空";
        }
        if (!IsbnUtils.valid(isbn)) {
            return "ISBN格式不正确";
        }
        if (trim(row.getTitle()).isEmpty()) {
            return "书名不能为空";
        }
        if (row.getStatus() != null && row.getStatus() != 0 && row.getStatus() != 1) {
            return "状态必须为 0 或 1";
        }
        if (row.getPrice() != null && row.getPrice().signum() < 0) {
            return "单价不能为负";
        }
        row.setIsbn(isbn);
        return null;
    }

    private String validateTeacherCourse(TeacherCourseImportRow row, int excelRow, ImportRunContext ctx) {
        String code = trim(row.getCourseCode());
        String courseName = trim(row.getCourseName());
        if (code.isEmpty() && courseName.isEmpty()) {
            return "课程代码与课程名至少填一个";
        }
        String teacherNo = trim(row.getTeacherNo());
        if (teacherNo.isEmpty()) {
            return "教师工号不能为空";
        }
        String className = trim(row.getClassName());
        if (className.isEmpty()) {
            return "班级名称不能为空";
        }
        // 学期：行内优先，缺省取导入目标学期
        Long rowSemesterId = ctx.semesterId();
        String semesterName = trim(row.getSemesterName());
        if (!semesterName.isEmpty()) {
            Semester semester = ctx.semester(semesterName);
            if (semester == null) {
                return "学期不存在";
            }
            rowSemesterId = semester.getId();
        }
        if (rowSemesterId == null) {
            return "目标学期不能为空";
        }
        Course course = null;
        if (!code.isEmpty()) {
            course = courseMapper.selectBySemesterAndCode(rowSemesterId, code);
        }
        if (course == null && !courseName.isEmpty()) {
            course = courseMapper.selectBySemesterAndName(rowSemesterId, courseName);
        }
        if (course == null) {
            return "课程不存在（课程代码/名称未匹配）";
        }
        SysUser teacher = ctx.user(teacherNo);
        if (teacher == null) {
            return "教师工号不存在";
        }
        if (!ctx.userHasRole(teacher.getId(), "TEACHER")) {
            return "该工号不是教师账号";
        }
        // 班级全局按名称匹配；同名多班取该教师学院下的，否则取第一个
        List<SchoolClass> classes = ctx.classesByName(className);
        if (classes.isEmpty()) {
            return "班级不存在";
        }
        Long teacherCollege = ctx.collegeOfUser(teacher.getId(), rowSemesterId);
        if (teacherCollege == null) {
            teacherCollege = teacher.getCollegeId();
        }
        final Long teacherCollegeId = teacherCollege;
        SchoolClass clazz = classes.stream()
                .filter(c -> teacherCollegeId != null && teacherCollegeId.equals(ctx.collegeOfClass(c)))
                .findFirst()
                .orElse(classes.get(0));
        row.setSemesterId(rowSemesterId);
        row.setCourseId(course.getId());
        row.setTeacherId(teacher.getId());
        row.setClassId(clazz.getId());
        return null;
    }

    // ============ 收尾 ============

    private void finalizeBatch(ImportRunContext ctx, ImportRunSummary summary) {
        List<java.util.Map<String, Object>> errors = summary.errors();
        String errorFilePath = errors.isEmpty() ? null : writeErrorFile(ctx.batchId(), errors);
        int disabledCount = 0;
        try {
            disabledCount = rowWriter.finishScope(ctx);
        } catch (Exception e) {
            log.error("导入收尾失败（班级人数/停用比对）: batchId={}", ctx.batchId(), e);
        }
        ImportBatch update = new ImportBatch();
        update.setId(ctx.batchId());
        update.setTotal(summary.total());
        update.setOkCount(summary.okCount());
        update.setErrorCount(errors.size());
        update.setProgressPct(100);
        update.setStatus("done");
        update.setErrorDetail(errors.isEmpty() ? null : errors);
        update.setErrorFilePath(errorFilePath);
        importBatchMapper.update(update, Wrappers.<ImportBatch>lambdaUpdate()
                .eq(ImportBatch::getId, ctx.batchId()));

        java.util.Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("bizType", ctx.bizType());
        detail.put("semesterId", ctx.semesterId());
        detail.put("total", summary.total());
        detail.put("okCount", summary.okCount());
        detail.put("errorCount", errors.size());
        detail.put("disabledCount", disabledCount);
        auditService.record(AuditService.IMPORT, "import_batch", String.valueOf(ctx.batchId()), detail);
        log.info("导入完成: batchId={}, bizType={}, total={}, ok={}, error={}, 停用={}",
                ctx.batchId(), ctx.bizType(), summary.total(), summary.okCount(), errors.size(), disabledCount);
    }

    private void markFailed(Long batchId, Exception e) {
        List<java.util.Map<String, Object>> detail = new ArrayList<>();
        java.util.Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("row", 0);
        entry.put("message", "导入失败：" + e.getMessage());
        detail.add(entry);
        ImportBatch update = new ImportBatch();
        update.setId(batchId);
        update.setStatus("failed");
        update.setProgressPct(100);
        update.setErrorDetail(detail);
        importBatchMapper.update(update, Wrappers.<ImportBatch>lambdaUpdate()
                .eq(ImportBatch::getId, batchId));
    }

    private String writeErrorFile(Long batchId, List<java.util.Map<String, Object>> errors) {
        try {
            Path dir = Path.of(properties.getExport().getTmpDir(), "import-errors");
            Files.createDirectories(dir);
            Path target = dir.resolve("import-errors-" + batchId + ".xlsx");
            List<ImportErrorRow> rows = errors.stream()
                    .map(e -> new ImportErrorRow(
                            e.get("row") instanceof Number n ? n.intValue() : 0,
                            String.valueOf(e.get("message"))))
                    .toList();
            try (OutputStream out = Files.newOutputStream(target)) {
                EasyExcel.write(out, ImportErrorRow.class).sheet("错误明细").doWrite(rows);
            }
            return target.toString();
        } catch (IOException e) {
            log.error("错误明细文件生成失败: batchId={}", batchId, e);
            return null;
        }
    }

    private void deleteQuietly(String filePath) {
        try {
            Files.deleteIfExists(Path.of(filePath));
        } catch (IOException e) {
            log.warn("导入临时文件删除失败: {}", filePath);
        }
    }

    private ImportRunContext newContext(Long batchId, String bizType, Long semesterId) {
        boolean writeUserAffiliation = semesterId != null
                && semesterId.equals(activeSemesterService.activeId());
        return new ImportRunContext(batchId, bizType, semesterId, writeUserAffiliation,
                collegeMapper, majorMapper, schoolClassMapper, userMapper, roleMapper,
                userRoleMapper, semesterMapper, profileMapper);
    }

    private <T> ImportReadListener<T> listener(ImportRunContext ctx,
                                              ImportReadListener.RowValidator<T> validator,
                                              ImportReadListener.RowFlusher<T> flusher) {
        return new ImportReadListener<>(ctx, importBatchMapper, validator, flusher, this::finalizeBatch);
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
