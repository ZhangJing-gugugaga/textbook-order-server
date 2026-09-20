package com.tian.textbook.approval.dto;

import com.tian.textbook.common.FieldCheckIssue;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 异动记录响应（逐条提交 / 我的提交记录 / 审批结果共用）。
 *
 * <p>before/after 展平自 payload_json（{before:{collegeId,classId}, after:{collegeId,classId}}）；
 * 姓名/学院名/班级名由 Service 批量回填。</p>
 */
@Data
public class ChangeRequestVO {

    private Long id;

    private Long semesterId;

    /** student/teacher */
    private String type;

    private Long targetUserId;

    private String targetUserNo;

    private String targetUserName;

    /** 变更前（当前）归属 */
    private Long beforeCollegeId;

    private String beforeCollegeName;

    private Long beforeClassId;

    private String beforeClassName;

    /** 变更后（目标）归属 */
    private Long afterCollegeId;

    private String afterCollegeName;

    private Long afterClassId;

    private String afterClassName;

    /** pending_review/approved/rejected */
    private String status;

    private String batchNo;

    private Long applicantId;

    private String applicantName;

    /** 审批人（超管）id */
    private Long reviewerId;

    /** 驳回理由 / 字段审查首条错误信息 / 导入行原因 */
    private String reason;

    /** 字段审查逐项错误（契约冻结回显格式） */
    private List<FieldCheckIssue> fieldCheckResult;

    private LocalDateTime reviewAt;

    private LocalDateTime createdAt;
}
