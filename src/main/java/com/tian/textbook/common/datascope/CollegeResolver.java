package com.tian.textbook.common.datascope;

/**
 * 当前用户学院归属解析（真源 user_semester_profile，W6）。
 *
 * <p>接口定义在 common，实现位于 semester 模块（UserScopeService），
 * 供 common 的 CollegeScopeHandler 使用，避免 common import 业务 Mapper。</p>
 */
public interface CollegeResolver {

    /**
     * @return 用户在指定学期的学院 id；无归属返回 null
     */
    Long collegeIdOf(Long userId, Long semesterId);
}
