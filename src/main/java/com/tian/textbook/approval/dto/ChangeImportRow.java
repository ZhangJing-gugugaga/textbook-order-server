package com.tian.textbook.approval.dto;

import com.alibaba.excel.annotation.ExcelIgnore;
import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

/**
 * 异动批量导入行模型（SPEC §10 异动模板：学号/工号、异动对象、目标学院、目标班级、原因、异动类型）。
 *
 * <p>按列下标映射；学院/班级按名称严格解析为 id，不存在即行错误。第 6 列「异动类型」（BE-7a）
 * 为**追加列**，缺列时归一为 {@code OTHER}（不阻断历史模板的导入）。第 2 列表头由「变更类型」
 * 改为「异动对象」——它表达的是 student/teacher，与新第 6 列的「异动类型」（转专业/留级/专升本/
 * 其他）语义不同；解析按下标，表头改名不影响既有数据。</p>
 */
@Data
public class ChangeImportRow {

    /** 列 1：学号/工号 */
    @ExcelProperty(index = 0)
    private String userNo;

    /** 列 2：异动对象（student/teacher，兼容「学生/教师」） */
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

    /** 列 6：异动类型（MAJOR_TRANSFER/GRADE_REPEAT/UPGRADE/OTHER，兼容中文；缺列 → OTHER） */
    @ExcelProperty(index = 5)
    private String changeType;

    // ============ 校验期填充（非 Excel 列；与 StudentImportRow 的 collegeId/classId 同模式） ============

    /** 解析后的异动对象（student/teacher） */
    @ExcelIgnore
    private String normalizedType;

    /** 解析后的目标用户 id（不存在为 null） */
    @ExcelIgnore
    private Long targetUserId;

    /** 解析后的目标学院 id */
    @ExcelIgnore
    private Long targetCollegeId;

    /** 解析后的目标班级 id */
    @ExcelIgnore
    private Long targetClassId;

    /** 字段审查结果（空 = 通过 → pending_review；非空 → rejected + field_check_result） */
    @ExcelIgnore
    private java.util.List<com.tian.textbook.common.FieldCheckIssue> issues = new java.util.ArrayList<>();
}
