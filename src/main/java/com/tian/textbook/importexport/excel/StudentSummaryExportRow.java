package com.tian.textbook.importexport.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 学生选购汇总导出行（参考用量，W18）：学院/班级/ISBN/书名/学生数/数量合计。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StudentSummaryExportRow {

    @ExcelProperty("学院")
    private String collegeName;

    @ExcelProperty("班级")
    private String className;

    @ExcelProperty("ISBN")
    private String isbn;

    @ExcelProperty("书名")
    private String title;

    /** 下单学生数（distinct student_id） */
    @ExcelProperty("学生数")
    private Integer studentCount;

    @ExcelProperty("数量合计")
    private Integer totalQuantity;
}
