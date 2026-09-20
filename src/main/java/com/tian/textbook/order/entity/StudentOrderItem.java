package com.tian.textbook.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 选购明细（student_order_item）。
 */
@Data
@TableName("student_order_item")
public class StudentOrderItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long textbookId;

    /** 1-9 且受班级人数约束（PRD 模块 5） */
    private Integer quantity;

    /** 教材来源课程（可追溯） */
    private Long courseId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private Long deleted;
}
