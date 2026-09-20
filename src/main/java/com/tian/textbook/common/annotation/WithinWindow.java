package com.tian.textbook.common.annotation;

import java.lang.annotation.*;

/**
 * 征订窗口校验注解（SPEC §6：挂在填报/选购提交接口；
 * window_status='open' && channel_open=1 才放行，违规 409）。
 *
 * <p>例外（W4）：被驳回表单的补正重提豁免，由 Exemption.CORRECTION 声明，
 * 具体判定委托 semester 模块 WindowGuard 实现。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WithinWindow {

    Exemption exemption() default Exemption.NONE;

    enum Exemption {
        /** 无豁免：窗口必须 open */
        NONE,
        /** 被驳回表单补正重提豁免（仅限本人该表单、状态 ∈ {rejected, rejected_auto}，且未过补正截止） */
        CORRECTION
    }
}
