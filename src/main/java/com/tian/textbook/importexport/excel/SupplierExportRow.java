package com.tian.textbook.importexport.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 供货商清单导出行（一学院一 sheet；字段白名单 = 书名/ISBN/数量/教师姓名/学院，W18）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupplierExportRow {

    @ExcelProperty("书名")
    private String title;

    @ExcelProperty("ISBN")
    private String isbn;

    @ExcelProperty("数量")
    private Integer quantity;

    @ExcelProperty("教师姓名")
    private String teacherName;

    @ExcelProperty("学院")
    private String collegeName;
}
