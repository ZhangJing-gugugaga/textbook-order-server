package com.tian.textbook.importexport.dto;

import java.util.List;
import java.util.Map;

/**
 * 导入预览（只读扫描，不落库）：局部名单防护（B13）与「导入前 diff 强确认」的数据源。
 *
 * <p>解析与行校验走与真实导入完全相同的一套校验器（外键/必填/格式），因此这里的
 * {@code okRows} / {@code classSizeDiffs} 就是「若此刻导入会发生的落库效果」——管理员据此
 * 判断这份文件是全量名单还是局部名单，再决定是否带 {@code confirmClassSizeShrink=true} 提交。</p>
 *
 * @param bizType                 业务类型（student / teacher / …）
 * @param semesterId              目标学期 id
 * @param totalRows               文件数据行数（含错误行）
 * @param okRows                  校验通过行数（= 会落库的行数）
 * @param errorRows               校验失败行数（含解析异常行）
 * @param errorSamples            错误明细样例（最多 20 条，全量明细在真实导入的批次里）
 * @param newUserCount            文件中「库内不存在」的学号/工号数（= 本次将新建的账号数）
 * @param disableComparisonApplies 是否执行了「不在名单内即停用」的比对（仅目标学期=active 学期时为真）
 * @param disableEstimate         比对将停用的账号数（{@code disableComparisonApplies=false} 时为 0）
 * @param classSizeDiffs          班级人数 diff（学生名单；其他类型为空）
 * @param requiresConfirm         是否存在命中阈值的下调 → 导入必须带 confirmClassSizeShrink=true
 * @param shrinkConfirmPct        当前生效的下调比例阈值（%）
 * @param shrinkConfirmMinDrop    当前生效的下调人数下限
 */
public record ImportPreviewResponse(
        String bizType,
        Long semesterId,
        int totalRows,
        int okRows,
        int errorRows,
        List<Map<String, Object>> errorSamples,
        int newUserCount,
        boolean disableComparisonApplies,
        int disableEstimate,
        List<ClassSizeDiff> classSizeDiffs,
        boolean requiresConfirm,
        int shrinkConfirmPct,
        int shrinkConfirmMinDrop) {
}
