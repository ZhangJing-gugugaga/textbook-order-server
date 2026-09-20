package com.tian.textbook.importexport.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 课程任课导入行（SPEC §10 模板列）：课程代码/课程名/教师工号/班级名称/学期。
 *
 * <p>upsert 键 = (semester_id, teacher_id, course_id, class_id)；学期列缺省取导入目标学期。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TeacherCourseImportRow {

    @ExcelProperty("课程代码")
    private String courseCode;

    @ExcelProperty("课程名")
    private String courseName;

    @ExcelProperty("教师工号")
    private String teacherNo;

    @ExcelProperty("班级名称")
    private String className;

    @ExcelProperty("学期")
    private String semesterName;

    // ---- 行校验时解析出的外键 id（EasyExcel 忽略） ----

    @ExcelIgnore
    private Long semesterId;

    @ExcelIgnore
    private Long courseId;

    @ExcelIgnore
    private Long teacherId;

    @ExcelIgnore
    private Long classId;
}
