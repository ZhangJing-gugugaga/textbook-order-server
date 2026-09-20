package com.tian.textbook.approval.dto;

import lombok.Data;

/**
 * 异动批量导入结果（SPEC §11.4：POST /api/secretary/change/import）。
 */
@Data
public class ChangeImportResult {

    /** import_batch.id（前端可轮询批次进度） */
    private Long batchId;

    /** 异动批次号（与 change_request.batch_no 对应，Q10） */
    private String batchNo;

    /** 解析到的数据行数（不含空行） */
    private int total;

    /** 字段审查通过（pending_review）行数 */
    private int okCount;

    /** 字段审查未通过（rejected + field_check_result）行数 */
    private int errorCount;
}
