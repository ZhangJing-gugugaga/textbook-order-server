package com.tian.textbook.supplier.dto;

/**
 * 供货商导出请求（POST /api/supplier/export，SPEC §11.5）。
 *
 * @param semesterId 目标学期，为空取当前 active 学期
 */
public record SupplierExportRequest(Long semesterId) {
}
