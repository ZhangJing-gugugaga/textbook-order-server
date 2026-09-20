package com.tian.textbook.approval.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.tian.textbook.common.FieldCheckIssue;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 异动审批（change_request，逐条/批量同链，Q10 批量共享 batch_no）。
 *
 * <p>状态机：pending_field_check → pending_review → approved（写 user_semester_profile
 * active 学期归属，W15 立即生效）/ rejected（理由必填）。</p>
 */
@Data
@TableName(value = "change_request", autoResultMap = true)
public class ChangeRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long semesterId;

    /** student/teacher */
    private String type;

    private Long targetUserId;

    /** 变更前后值 {before:{...}, after:{...}}；批量时逐行一条 */
    @TableField(value = "payload_json", typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> payloadJson;

    private String status;

    @TableField(value = "field_check_result", typeHandler = JacksonTypeHandler.class)
    private List<FieldCheckIssue> fieldCheckResult;

    /** 批量导入批次号 */
    private String batchNo;

    private Long applicantId;

    private Long reviewerId;

    private LocalDateTime reviewAt;

    /** 驳回理由 1-200 字 */
    private String reason;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private Long createdBy;

    private Long updatedBy;

    private Long deleted;
}
