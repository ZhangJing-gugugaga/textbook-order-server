package com.tian.textbook.importexport.support;

import java.util.List;
import java.util.Map;

/**
 * 单次导入运行结果（监听器结束汇总 → 批次终态）。
 *
 * @param total   数据行总数（含错误行）
 * @param okCount 成功落库行数
 * @param errors  行级错误 [{row,message}]（error_detail JSON + 错误明细 xlsx）
 */
public record ImportRunSummary(int total, int okCount, List<Map<String, Object>> errors) {
}
