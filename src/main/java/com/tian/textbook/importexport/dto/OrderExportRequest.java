package com.tian.textbook.importexport.dto;

/**
 * 教师征订明细导出请求（POST /api/admin/export/orders）。
 *
 * @param semesterId 学期（缺省 = 当前 active 学期）
 * @param collegeId  学院（秘书本院；超管可缺省查全院）
 */
public record OrderExportRequest(Long semesterId, Long collegeId) {
}
