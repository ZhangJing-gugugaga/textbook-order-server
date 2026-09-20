package com.tian.textbook.common.annotation;

import java.lang.annotation.*;

/**
 * 数据隔离注解（W10：MyBatis-Plus DataPermissionInterceptor 自动改写 SQL）。
 *
 * <p>标注在 Mapper 方法上即对该语句追加隔离条件；未标注不隔离（新表接入必须显式标注）。</p>
 *
 * <p>多角色并集（OR 连接各角色范围）：ADMIN 不过滤；SECRETARY 按 secretaryColumn 比较
 * 当前用户学院（真源 user_semester_profile）；TEACHER 按 teacherColumn 比较当前用户 id；
 * STUDENT 按 studentColumn 比较当前用户 id；userColumn 为通用「本人」列。</p>
 *
 * <p>⚠️ 只有主表真实存在对应列时才配置该列名——为无 college_id 列的方法配置学院列会在
 * 多角色（秘书+教师）并集时生成错误 SQL。跨表学院范围（如秘书查看本院教师表单，主表无
 * college_id）不使用本注解，由 Service 显式传参 + Mapper XML join 实现，隔离效果等同。</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CollegeScope {

    /** 学院列（SECRETARY 范围），空表示不适用（默认空：防止误配生成错误 SQL） */
    String secretaryColumn() default "";

    /** 教师维度列（TEACHER 范围，比较当前用户 id），空表示不适用 */
    String teacherColumn() default "";

    /** 学生维度列（STUDENT 范围，比较当前用户 id），空表示不适用 */
    String studentColumn() default "";

    /** 通用本人列（比较当前用户 id），空表示不适用 */
    String userColumn() default "";
}
