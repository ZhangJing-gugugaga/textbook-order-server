package com.tian.textbook.importexport.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 通知汇总导出请求（POST /api/admin/export/notice）。
 *
 * @param taskId 通知任务 id（学期由任务归属推导）
 */
public record NoticeExportRequest(@NotNull(message = "通知任务不能为空") Long taskId) {
}
