package com.tian.textbook.importexport.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 教师征订明细导出行（秘书本院/教材室全院）：学院/教师/工号/课程/班级/ISBN/书名/数量。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderExportRow {

    @ExcelProperty("学院")
    private String collegeName;

    @ExcelProperty("教师")
    private String teacherName;

    @ExcelProperty("工号")
    private String teacherNo;

    @ExcelProperty("课程")
    private String courseName;

    @ExcelProperty("班级")
    private String className;

    @ExcelProperty("ISBN")
    private String isbn;

    @ExcelProperty("书名")
    private String title;

    @ExcelProperty("数量")
    private Integer quantity;
}
