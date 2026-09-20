package com.tian.textbook.importexport.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 学生全量导入行（SPEC §10 模板列，M1 冻结 W13）：学号/姓名/学院/专业/班级/手机号。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StudentImportRow {

    @ExcelProperty("学号")
    private String userNo;

    @ExcelProperty("姓名")
    private String name;

    @ExcelProperty("学院")
    private String collegeName;

    @ExcelProperty("专业")
    private String majorName;

    @ExcelProperty("班级")
    private String className;

    @ExcelProperty("手机号")
    private String phone;

    // ---- 行校验时解析出的外键 id（EasyExcel 忽略；校验与落库共用，避免重复查询） ----

    @ExcelIgnore
    private Long collegeId;

    @ExcelIgnore
    private Long classId;
}
