package com.tian.textbook.approval.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 异动批量导入行模型（SPEC §10 异动模板，M1 冻结列顺序：学号/工号、变更类型、目标学院、目标班级、原因）。
 *
 * <p>按列下标映射（模板无多余列）；学院/班级按名称严格解析为 id，不存在即行错误。</p>
 */
@Data
public class ChangeImportRow {

    /** 列 1：学号/工号 */
    @ExcelProperty(index = 0)
    private String userNo;

    /** 列 2：变更类型（student/teacher，兼容「学生/教师」） */
    @ExcelProperty(index = 1)
    private String type;

    /** 列 3：目标学院名称 */
    @ExcelProperty(index = 2)
    private String collegeName;

    /** 列 4：目标班级名称（teacher 行应为空） */
    @ExcelProperty(index = 3)
    private String className;

    /** 列 5：原因 */
    @ExcelProperty(index = 4)
    private String reason;
}
