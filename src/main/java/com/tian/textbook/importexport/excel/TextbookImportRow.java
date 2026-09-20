package com.tian.textbook.importexport.excel;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 教材库导入行（SPEC §10 模板列）：ISBN/书名/版次/作者/出版社/单价/状态。
 *
 * <p>跨学期共用（B5），upsert 键 = isbn，semesterId 可空。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TextbookImportRow {

    @ExcelProperty("ISBN")
    private String isbn;

    @ExcelProperty("书名")
    private String title;

    @ExcelProperty("版次")
    private String edition;

    @ExcelProperty("作者")
    private String author;

    @ExcelProperty("出版社")
    private String press;

    @ExcelProperty("单价")
    private BigDecimal price;

    /** 1 在库 0 停用；空 = 1 */
    @ExcelProperty("状态")
    private Integer status;
}
