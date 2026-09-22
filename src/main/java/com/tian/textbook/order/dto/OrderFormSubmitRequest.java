package com.tian.textbook.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 教师征订提交请求（POST /api/teacher/order-form/submit）。
 *
 * <p>覆盖语义：重提 = 整单覆盖（先逻辑删旧明细再插新，SPEC §12）；
 * 字段审查全过 → pending_review，任一不过 → rejected_auto + 逐字段错误。</p>
 *
 * <p>明细条数上限 {@value #MAX_ITEMS}：写入是逐条 INSERT，无上限时一次请求可插入任意行数，
 * 单请求即可长时间占用连接并撑爆明细表（正常一单 = 本人任课关系的课程×班级组合数，量级为几十）。</p>
 */
public record OrderFormSubmitRequest(

        @NotEmpty(message = "征订明细不能为空")
        @Size(max = MAX_ITEMS, message = "征订明细不能超过 " + MAX_ITEMS + " 条")
        @Valid
        List<OrderFormSubmitItem> items) {

    /** 单次提交明细条数上限 */
    public static final int MAX_ITEMS = 500;
}
