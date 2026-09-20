package com.tian.textbook.importexport.dto;

/**
 * 秘书签字版导出请求（POST /api/secretary/export/signature）。
 *
 * <p>学院范围 = 当前用户 active 学期归属（user_semester_profile，W6），不接受传入 collegeId。</p>
 *
 * @param semesterId 学期（缺省 = 当前 active 学期）
 */
public record SignatureExportRequest(Long semesterId) {
}
