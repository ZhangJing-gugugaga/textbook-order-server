package com.tian.textbook.order.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 征订表单列表项（秘书本院 / 超管全院共用）。
 */
@Data
public class OrderFormListItem {

    private Long id;

    private Long semesterId;

    private Long teacherId;

    private String teacherName;

    private String teacherNo;

    private Long collegeId;

    private String collegeName;

    /** draft/submitted/rejected_auto/rejected/pending_review/reviewed */
    private String status;

    private LocalDateTime submittedAt;

    private LocalDateTime reviewAt;

    private String reviewNote;

    private LocalDateTime correctDeadline;

    /** 明细行数 */
    private Integer itemCount;

    /** 数量合计 */
    private Integer totalQuantity;
}
