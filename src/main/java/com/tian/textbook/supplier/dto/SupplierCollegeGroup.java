package com.tian.textbook.supplier.dto;

import lombok.Data;

import java.util.List;

/**
 * 供货商清单 · 按学院分组（GET /api/supplier/orders，SPEC §11.5）。
 */
@Data
public class SupplierCollegeGroup {

    private Long collegeId;

    private String collegeName;

    private List<SupplierOrderItem> items;
}
