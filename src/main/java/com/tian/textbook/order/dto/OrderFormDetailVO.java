package com.tian.textbook.order.dto;

import com.tian.textbook.common.FieldCheckIssue;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 征订单详情 VO（教师当前单 / 超管详情 / 提交回显共用）。
 *
 * <p>含 field_check_result（契约冻结回显 [{field,rule,message}]）与明细（带课程名/班级名/教材名/ISBN）。</p>
 */
@Data
public class OrderFormDetailVO {

    private Long id;

    private Long semesterId;

    private String semesterName;

    private Long teacherId;

    /** draft/submitted/rejected_auto/rejected/pending_review/reviewed */
    private String status;

    /** 字段审查结果 [{field,rule,message}]；null 表示审查通过或无历史错误 */
    private List<FieldCheckIssue> fieldCheckResult;

    private LocalDateTime submittedAt;

    private LocalDateTime reviewAt;

    private Long reviewBy;

    /** 驳回理由 1-200 字 */
    private String reviewNote;

    /** 补正截止（关窗后 order.correct_window_days 天，W4） */
    private LocalDateTime correctDeadline;

    /**
     * 内容版本（每次教师提交/补正整单覆盖后 +1）。
     *
     * <p>审核端**必须回传**本字段（{@code POST /api/admin/order-forms/{id}/review} 的
     * {@code contentVersion}）：审核页打开后教师可能又重提过一次，服务端仅凭状态无法发现
     * （状态仍是 pending_review，明细已被整单覆盖）。前端把详情里读到的值原样回传，
     * 服务端以它作为 CAS 谓词；不一致即 409，避免审批结论落在没见过的内容上。</p>
     */
    private Integer contentVersion;

    private List<OrderFormItemVO> items;

    /** 明细行数 */
    private Integer itemCount;

    /** 数量合计 */
    private Integer totalQuantity;
}
