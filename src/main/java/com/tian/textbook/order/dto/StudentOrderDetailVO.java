package com.tian.textbook.order.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 学生选购单详情 VO（GET /api/student/order / 提交回显）。
 */
@Data
public class StudentOrderDetailVO {

    private Long id;

    private Long semesterId;

    private String semesterName;

    /** draft/submitted */
    private String status;

    /** 提交时归属快照 {collegeId,collegeName,classId,className}，异动不影响历史归属（W15） */
    private Map<String, Object> submitSnapshot;

    private LocalDateTime submittedAt;

    private List<StudentOrderItemVO> items;

    /** 数量合计 */
    private Integer totalQuantity;
}
