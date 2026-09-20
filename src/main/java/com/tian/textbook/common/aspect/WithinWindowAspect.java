package com.tian.textbook.common.aspect;

import com.tian.textbook.common.annotation.WithinWindow;
import com.tian.textbook.common.window.WindowGuard;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/**
 * 征订窗口校验切面（SPEC §6：@WithinWindow 挂在填报/选购提交接口；
 * window_status='open' && channel_open=1 才放行，违规 409；被驳回表单补正豁免 W4）。
 *
 * <p>具体判定委托 semester 模块的 WindowGuard 实现（common 不依赖业务 Mapper）。</p>
 */
@Aspect
@Component
@RequiredArgsConstructor
public class WithinWindowAspect {

    private final WindowGuard windowGuard;

    @Before("@annotation(withinWindow)")
    public void before(WithinWindow withinWindow) {
        windowGuard.assertWithinWindow(withinWindow.exemption());
    }
}
