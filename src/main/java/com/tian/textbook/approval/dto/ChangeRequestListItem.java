package com.tian.textbook.approval.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 异动审批列表项。
 */
@Data
public class ChangeRequestListItem {

    private Long id;

    private Long semesterId;

    /** student/teacher（异动对象） */
    private String type;

    /** 异动类型（MAJOR_TRANSFER/GRADE_REPEAT/UPGRADE/OTHER；历史数据为 null） */
    private String changeType;

    /** 异动类型中文（转专业/留级/专升本/其他；null → 未分类） */
    private String changeTypeLabel;

    private Long targetUserId;

    private String targetUserName;

    private String targetUserNo;

    private String currentCollegeName;

    private String currentClassName;

    /** 变更前后值 {before:{...}, after:{...}} */
    private Map<String, Object> payloadJson;

    private String status;

    private String batchNo;

    private Long applicantId;

    private String applicantName;

    private String reason;

    private LocalDateTime createdAt;

    private LocalDateTime reviewAt;
}
