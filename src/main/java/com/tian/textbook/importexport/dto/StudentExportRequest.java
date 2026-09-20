package com.tian.textbook.importexport.dto;

/**
 * 学生选购汇总导出请求（POST /api/admin/export/students，参考用量 W18）。
 *
 * @param semesterId 学期（缺省 = 当前 active 学期）
 */
public record StudentExportRequest(Long semesterId) {
}
