package com.tian.textbook.importexport.dto;

import java.util.Map;

/**
 * 导出分发计划（阈值判定结果，SPEC §10 / Q16）。
 *
 * <p>Controller 依据 {@link #async()} 决定返回 taskId（前端轮询）还是直接把
 * {@link ExportService 的} writeSync 写入响应流；params 为同步/异步共用的生成参数。</p>
 *
 * @param async       true = 已创建异步任务，返回 taskId
 * @param taskId      异步任务 id（同步为 null）
 * @param bizType     order/signature/student/notice/supplier
 * @param params      生成参数（semesterId/collegeId/taskId）
 * @param fileName    同步下载文件名
 * @param rowEstimate 预估行数
 */
public record ExportPlan(boolean async, Long taskId, String bizType,
                         Map<String, Object> params, String fileName, int rowEstimate) {
}
