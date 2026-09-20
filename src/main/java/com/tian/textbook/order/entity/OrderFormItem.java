package com.tian.textbook.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 征订明细（order_form_item：课程×班级×教材×数量，1 ≤ 数量 ≤ 班级人数，W2）。
 */
@Data
@TableName("order_form_item")
public class OrderFormItem {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long formId;

    private Long courseId;

    private Long classId;

    private Long textbookId;

    private Integer quantity;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private Long deleted;
}
