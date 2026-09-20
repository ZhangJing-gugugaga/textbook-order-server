package com.tian.textbook.semester;

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
 * （correct_deadline = 关窗时间 + order.correct_window_days）。</p>
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
        if (exemption == WithinWindow.Exemption.CORRECTION && isCorrectionAllowed(semesterId)) {
            return;
        }
        if (Integer.valueOf(1).equals(semester.getChannelOpen()) && "open".equals(semester.getWindowStatus())) {
            return;
        }
        if ("closed".equals(semester.getWindowStatus())) {
            throw new BizException(ErrorCode.WINDOW_CLOSED);
        }
        throw new BizException(ErrorCode.WINDOW_NOT_OPEN);
    }

    /** 补正豁免：本人该表单状态 ∈ {rejected, rejected_auto} 且未过 correct_deadline */
    private boolean isCorrectionAllowed(Long semesterId) {
        var current = com.tian.textbook.common.SecurityUtils.currentUser();
        if (current == null) {
            return false;
        }
        OrderForm form = orderFormMapper.selectBySemesterAndTeacher(semesterId, current.userId());
        if (form == null || !Set.of("rejected", "rejected_auto").contains(form.getStatus())) {
            return false;
        }
        LocalDateTime deadline = form.getCorrectDeadline();
        return deadline == null || deadline.isAfter(LocalDateTime.now());
    }
}
