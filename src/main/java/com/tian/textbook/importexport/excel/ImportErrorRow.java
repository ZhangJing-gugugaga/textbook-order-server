package com.tian.textbook.importexport.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 导入错误明细行（error_detail JSON + 错误明细 xlsx，SPEC §10）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImportErrorRow {

    /** Excel 行号（含表头，第 1 行为表头） */
    @ExcelProperty("行号")
    private Integer row;

    @ExcelProperty("错误信息")
    private String message;
}
