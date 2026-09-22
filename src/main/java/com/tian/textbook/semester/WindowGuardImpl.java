package com.tian.textbook.semester;

import com.tian.textbook.common.util.AppTime;
import com.tian.textbook.common.annotation.WithinWindow;
import com.tian.textbook.common.error.BizException;
import com.tian.textbook.common.error.ErrorCode;
import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.common.window.WindowGuard;
import com.tian.textbook.order.entity.OrderForm;
import com.tian.textbook.order.mapper.OrderFormMapper;
import com.tian.textbook.semester.entity.Semester;
import com.tian.textbook.semester.mapper.SemesterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 窗口校验实现（SPEC §6 @WithinWindow）。
 *
 * <p>放行条件：window_status='open' && channel_open=1。例外（W4）：被驳回表单的补正重提
 * 豁免——仅限本人该表单、状态 ∈ {rejected, rejected_auto}、且未过补正截止
 * （correct_deadline = 关窗时间 + order.correct_window_days）；补正截止已过 →
 * 409 CORRECTION_EXPIRED（区别于「本期征订已截止」的精确语义）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WindowGuardImpl implements WindowGuard {

    private final SemesterMapper semesterMapper;
    private final OrderFormMapper orderFormMapper;

    @Override
    public void assertWithinWindow(WithinWindow.Exemption exemption) {
        Long semesterId = SemesterContextHolder.get();
        if (semesterId == null) {
            throw new BizException(ErrorCode.BIZ_ERROR, "尚未激活任何学期，请联系教材室");
        }
        Semester semester = semesterMapper.selectByIdSoft(semesterId);
        if (semester == null) {
            throw new BizException(ErrorCode.BIZ_ERROR, "尚未激活任何学期，请联系教材室");
        }
        if (exemption == WithinWindow.Exemption.CORRECTION) {
            switch (correctionState(semesterId)) {
                case ALLOWED -> {
                    return;
                }
                case EXPIRED -> throw new BizException(ErrorCode.CORRECTION_EXPIRED);
                case NOT_APPLICABLE -> {
                    // 非补正场景：走常规窗口校验
                }
            }
        }
        if (Integer.valueOf(1).equals(semester.getChannelOpen()) && "open".equals(semester.getWindowStatus())) {
            return;
        }
        if ("closed".equals(semester.getWindowStatus())) {
            throw new BizException(ErrorCode.WINDOW_CLOSED);
        }
        throw new BizException(ErrorCode.WINDOW_NOT_OPEN);
    }

    /**
     * 补正豁免状态：本人该表单状态 ∈ {rejected, rejected_auto} 且未过 correct_deadline。
     *
     * <p>deadline 为空时<b>不</b>放行：豁免必须有时限兜底，否则教师只要提交一行必失败的数据
     * 拿到 rejected_auto（该分支此前从不写 correct_deadline），就永久获得豁免，
     * 关窗后乃至学期归档后仍可提交。无截止时间 = 无可放行窗口 → EXPIRED。</p>
     */
    private CorrectionState correctionState(Long semesterId) {
        var current = com.tian.textbook.common.SecurityUtils.currentUser();
        if (current == null) {
            return CorrectionState.NOT_APPLICABLE;
        }
        OrderForm form = orderFormMapper.selectBySemesterAndTeacher(semesterId, current.userId());
        if (form == null || !Set.of("rejected", "rejected_auto").contains(form.getStatus())) {
            return CorrectionState.NOT_APPLICABLE;
        }
        LocalDateTime deadline = form.getCorrectDeadline();
        if (deadline == null || !deadline.isAfter(AppTime.now())) {
            // 补正窗口已过（或未设定截止）：区别于「本期征订已截止」的精确语义（SPEC §6 / 错误码表）
            return CorrectionState.EXPIRED;
        }
        return CorrectionState.ALLOWED;
    }

    private enum CorrectionState {
        /** 补正重提豁免成立（表单被驳回且补正截止未过） */
        ALLOWED,
        /** 表单被驳回但补正截止已过 → 409 CORRECTION_EXPIRED */
        EXPIRED,
        /** 无被驳回表单 → 不适用豁免，走常规窗口校验 */
        NOT_APPLICABLE
    }
}
