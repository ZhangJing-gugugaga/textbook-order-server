package com.tian.textbook.supplier.dto;

import lombok.Data;

/**
 * 供货商清单行（SupplierOrderMapper resultType，物理隔离：SQL 直连 join，PRD 模块 8）。
 *
 * <p>字段白名单 = 书名/ISBN/数量/教师姓名/学院（W18：不含学生数据、不含价格）。</p>
 */
@Data
public class SupplierOrderRow {

    private Long collegeId;

    private String collegeName;

    private String teacherName;

    private String isbn;

    private String title;

    private Integer quantity;
}
