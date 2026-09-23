package com.tian.textbook.importexport.template;

import com.alibaba.excel.EasyExcel;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.importexport.excel.StudentImportRow;
import com.tian.textbook.importexport.excel.TeacherCourseImportRow;
import com.tian.textbook.importexport.excel.TeacherImportRow;
import com.tian.textbook.importexport.excel.TextbookImportRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 导入/导出模板服务（SPEC §10：模板列 M1 冻结 W13；单一来源 = 代码，xlsx 文件随构建生成）。
 *
 * <p>运行时模板下载直接用 EasyExcel 流式写出（不读 classpath 文件，保证模板与
 * {@code importexport.excel} 行模型永远一致）；{@code src/main/resources/templates/*.xlsx}
 * 为构建期产物，供运维留档与签字版导出取版式。</p>
 */
@Slf4j
@Service
public class TemplateService {

    public static final String STUDENT_FILE = "student.xlsx";
    public static final String TEACHER_FILE = "teacher.xlsx";
    public static final String TEXTBOOK_FILE = "textbook.xlsx";
    public static final String TEACHER_COURSE_FILE = "teacher-course.xlsx";
    public static final String CHANGE_FILE = "change.xlsx";
    public static final String SIGNATURE_FILE = "secretary-signature.xlsx";

    public static final List<String> TEMPLATE_FILES =
            List.of(STUDENT_FILE, TEACHER_FILE, TEXTBOOK_FILE, TEACHER_COURSE_FILE, CHANGE_FILE, SIGNATURE_FILE);

    private static final String CLASS_PATH_PREFIX = "templates/";

    private static final String DEFAULT_SIGNATURE_TITLE = "（学院名称）（学期名称）教材征订签字版";
    private static final String[] SIGNATURE_HEADERS =
            {"学院", "教师", "工号", "课程", "班级", "ISBN", "书名", "数量", "签字"};
    private static final String[] CHANGE_HEADERS =
            {"学号/工号", "异动对象", "目标学院", "目标班级", "原因", "异动类型"};

    /** bizType → 模板文件名（textbook/teacher_course 复用教材/任课模板） */
    public static String templateFileOf(String bizType) {
        return switch (bizType) {
            case "student" -> STUDENT_FILE;
            case "teacher" -> TEACHER_FILE;
            case "textbook" -> TEXTBOOK_FILE;
            case "teacher_course" -> TEACHER_COURSE_FILE;
            case "change" -> CHANGE_FILE;
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "未知导入类型: " + bizType);
        };
    }

    /** 按 bizType 流式写模板（端点直接调用） */
    public void writeTemplate(String bizType, OutputStream out) {
        switch (bizType) {
            case "student" -> writeStudent(out);
            case "teacher" -> writeTeacher(out);
            case "textbook" -> writeTextbook(out);
            case "teacher_course" -> writeTeacherCourse(out);
            case "change" -> writeChange(out);
            default -> throw new BizException(ErrorCode.PARAM_INVALID, "未知导入类型: " + bizType);
        }
    }

    public void writeStudent(OutputStream out) {
        writeWithHead(out, StudentImportRow.class, "学生名单");
    }

    public void writeTeacher(OutputStream out) {
        writeWithHead(out, TeacherImportRow.class, "教师名单");
    }

    public void writeTextbook(OutputStream out) {
        writeWithHead(out, TextbookImportRow.class, "教材库");
    }

    public void writeTeacherCourse(OutputStream out) {
        writeWithHead(out, TeacherCourseImportRow.class, "课程任课");
    }

    public void writeChange(OutputStream out) {
        // 列优先表头：6 列各 1 个单元格（异动导入按下标读取，列顺序即契约；第 6 列「异动类型」为 BE-7a 追加列）
        List<List<String>> columns = new ArrayList<>();
        for (String header : CHANGE_HEADERS) {
            columns.add(List.of(header));
        }
        EasyExcel.write(out).head(columns).sheet("异动批量").doWrite(new ArrayList<>());
    }

    /**
     * 签字版模板（SPEC §10：标准表格 + 签字栏三行占位：学院盖章 / 教材室签字 / 日期）。
     * 田老师样张到位后替换 templates/secretary-signature.xlsx，代码不改。
     *
     * <p>注意 EasyExcel 复杂表头是「列优先」：head 的每个内层 List 是一列（自上而下），
     * 因此标题行与表头行要按列组装。</p>
     */
    public void writeSignature(OutputStream out) {
        List<List<String>> columns = new ArrayList<>();
        for (int col = 0; col < SIGNATURE_HEADERS.length; col++) {
            columns.add(List.of(col == 0 ? DEFAULT_SIGNATURE_TITLE : "", SIGNATURE_HEADERS[col]));
        }

        List<List<Object>> data = new ArrayList<>();
        data.add(emptyRow());
        data.add(signatureRow("学院盖章：______"));
        data.add(signatureRow("教材室签字：______"));
        data.add(signatureRow("日期：______"));

        EasyExcel.write(out).head(columns).sheet("签字版").doWrite(data);
    }

    /** classpath 模板是否存在（签字版导出取版式时判断） */
    public boolean templateExists(String fileName) {
        return new ClassPathResource(CLASS_PATH_PREFIX + fileName).exists();
    }

    /** 读取 classpath 模板（不存在返回 null） */
    public InputStream openClasspathTemplate(String fileName) {
        try {
            ClassPathResource resource = new ClassPathResource(CLASS_PATH_PREFIX + fileName);
            return resource.exists() ? resource.getInputStream() : null;
        } catch (IOException e) {
            log.warn("读取模板失败: {}", fileName, e);
            return null;
        }
    }

    /**
     * 生成全部模板文件到目录（构建期产物 / 运维重建；写失败不影响端点）。
     * 运行时下载不走这里（EasyExcel 直接写响应流）。
     */
    public void writeTemplateFiles(Path dir) throws IOException {
        Files.createDirectories(dir);
        writeFile(dir.resolve(STUDENT_FILE), this::writeStudent);
        writeFile(dir.resolve(TEACHER_FILE), this::writeTeacher);
        writeFile(dir.resolve(TEXTBOOK_FILE), this::writeTextbook);
        writeFile(dir.resolve(TEACHER_COURSE_FILE), this::writeTeacherCourse);
        writeFile(dir.resolve(CHANGE_FILE), this::writeChange);
        writeFile(dir.resolve(SIGNATURE_FILE), this::writeSignature);
        log.info("模板文件已生成: {}", dir.toAbsolutePath());
    }

    private void writeFile(Path target, java.util.function.Consumer<OutputStream> writer) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            writer.accept(out);
        }
    }

    private void writeWithHead(OutputStream out, Class<?> headClass, String sheetName) {
        EasyExcel.write(out, headClass).sheet(sheetName).doWrite(new ArrayList<>());
    }

    private List<Object> emptyRow() {
        List<Object> row = new ArrayList<>();
        for (int i = 0; i < SIGNATURE_HEADERS.length; i++) {
            row.add("");
        }
        return row;
    }

    private List<Object> signatureRow(String text) {
        List<Object> row = emptyRow();
        row.set(0, text);
        return row;
    }
}
