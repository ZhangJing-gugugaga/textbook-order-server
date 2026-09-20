package com.tian.textbook.order.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 学生选购列表项（超管全院查看）。
 */
@Data
public class StudentOrderListItem {

    private Long id;

    private Long semesterId;

    private String semesterName;

    private Long studentId;

    private String studentName;

    private String studentNo;

    private Long collegeId;

    private String collegeName;

    private Long classId;

    private String className;

    /** draft/submitted */
    private String status;

    private LocalDateTime submittedAt;

    /** 提交时归属快照 */
    private Map<String, Object> submitSnapshot;

    private Integer totalQuantity;
}
