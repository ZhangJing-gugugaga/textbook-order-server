package com.tian.textbook.supplier.dto;

import lombok.Data;

/**
 * 供货商清单明细项（按学院分组内的行）。
 */
@Data
public class SupplierOrderItem {

    private String teacherName;

    private String isbn;

    private String title;

    private Integer quantity;
}
