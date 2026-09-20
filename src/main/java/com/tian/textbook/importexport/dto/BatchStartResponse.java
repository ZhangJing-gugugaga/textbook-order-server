package com.tian.textbook.importexport.dto;

/**
 * 导入启动响应（SPEC §11.3：返回 batchId，前端轮询 GET /api/batch/{batchId}）。
 */
public record BatchStartResponse(Long batchId) {
}
