package com.tian.textbook.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.tian.textbook.common.FieldCheckIssue;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 教师征订单（order_form，一人一学期一单，W9 唯一约束）。
 *
 * <p>状态机：draft → submitted →（字段审查）rejected_auto / pending_review →（超管审核）
 * reviewed / rejected（rejected 可补正重提，关窗后 7 天内仍可补正，W4）。</p>
 */
@Data
@TableName(value = "order_form", autoResultMap = true)
public class OrderForm {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long semesterId;

    /** 教师用户 id */
    private Long teacherId;

    private String status;

    /** 字段审查结果 [{field,rule,message}]（契约冻结项） */
    @TableField(value = "field_check_result", typeHandler = JacksonTypeHandler.class)
    private List<FieldCheckIssue> fieldCheckResult;

    private Long reviewBy;

    private LocalDateTime reviewAt;

    /** 驳回理由 1-200 字 */
    private String reviewNote;

    private LocalDateTime submittedAt;

    /** 补正截止（关窗后 order.correct_window_days 天，W4） */
    private LocalDateTime correctDeadline;

    /**
     * 内容版本（每次教师提交/补正整单覆盖后 +1）。
     *
     * <p>审核的 CAS 谓词除 status 外还比对本列：只比对 status 无法发现
     * 「管理员打开详情后教师又重提过」——状态仍是 pending_review，但明细已被整单覆盖，
     * 审批结论会落在管理员没见过的内容上（审核对象漂移）。</p>
     */
    private Integer contentVersion;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private Long deleted;
}
