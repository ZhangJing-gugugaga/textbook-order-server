package com.tian.textbook.semester.context;

import com.tian.textbook.common.semester.SemesterContextHolder;
import com.tian.textbook.semester.SemesterActiveService;
import com.tian.textbook.semester.entity.Semester;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 学期上下文拦截器（SPEC §5.4：请求进入时把 activeSemesterId 写入 ThreadLocal，
 * 全程使用该快照；切换瞬间在途请求按旧学期快照完成，不串学期）。
 */
@Component
@RequiredArgsConstructor
public class SemesterContextInterceptor implements HandlerInterceptor {

    private final SemesterActiveService activeSemesterService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        Semester active = activeSemesterService.active();
        SemesterContextHolder.set(active == null ? null : active.getId());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        SemesterContextHolder.clear();
    }
}
