package com.tian.textbook.common.annotation;

import java.lang.annotation.*;

/**
 * 审计注解（system 模块 AuditLogAspect 拦截，与业务操作同事务落库，不含密码/token）。
 *
 * @param action   动作令牌（LOGIN/EXPORT/ACCOUNT/WINDOW/SEMESTER_SWITCH/REVIEW/CHANGE/CONFIG…）
 * @param resource 资源名（可选，缺省取方法名前缀）
 * @param recordArgs 是否将请求参数摘要写入 detail_json（敏感字段需调用方自行规避）
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditLog {

    String action();

    String resource() default "";

    boolean recordArgs() default false;

    /** detail_json 中资源 id 的参数名（如 "id"），缺省不记录。 */
    String resourceIdParam() default "";
}
