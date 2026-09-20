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

    /** student/teacher */
    private String type;

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
