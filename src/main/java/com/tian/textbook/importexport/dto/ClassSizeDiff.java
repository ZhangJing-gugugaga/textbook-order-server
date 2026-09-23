package com.tian.textbook.importexport.dto;

/**
 * 单个班级的人数 diff（局部名单防护，B13）。
 *
 * @param classId        班级 id
 * @param className      班级名称（提示文案用）
 * @param majorName      专业名称（可为 null）
 * @param collegeName    学院名称（可为 null）
 * @param currentCount   库中当前人数（{@code school_class.student_count}，教师填报数量上限来源）
 * @param incomingCount  本次文件内该班去重学生数（导入后将写入的新值）
 * @param drop           下调人数（{@code currentCount - incomingCount}，&le;0 表示未下调）
 * @param dropPct        下调比例（四舍五入的百分数，未下调为 0）
 * @param requiresConfirm 是否命中「疑似局部名单」阈值，需显式确认后才能导入
 */
public record ClassSizeDiff(
        Long classId,
        String className,
        String majorName,
        String collegeName,
        int currentCount,
        int incomingCount,
        int drop,
        int dropPct,
        boolean requiresConfirm) {

    public String describe() {
        return String.format("%s（%s/%s）%d → %d（-%d，%d%%）",
                className, blankToDash(collegeName), blankToDash(majorName),
                currentCount, incomingCount, drop, dropPct);
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
