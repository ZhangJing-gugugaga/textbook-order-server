package com.tian.textbook.importexport.excel;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 教师全量导入行（SPEC §10 模板列）：工号/姓名/学院/手机号。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TeacherImportRow {

    @ExcelProperty("工号")
    private String userNo;

    @ExcelProperty("姓名")
    private String name;

    @ExcelProperty("学院")
    private String collegeName;

    @ExcelProperty("手机号")
    private String phone;

    /** 行校验时解析出的学院 id（EasyExcel 忽略） */
    @ExcelIgnore
    private Long collegeId;
}
