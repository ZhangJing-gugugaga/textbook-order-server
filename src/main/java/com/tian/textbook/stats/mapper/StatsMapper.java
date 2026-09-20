package com.tian.textbook.stats.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 看板统计查询（stats 包自建，注解 SQL，无 XML）。
 *
 * <p>教师数口径：active 学期 user_semester_profile 在册 + TEACHER 角色 + 账号正常
 * （profile 为归属真源，W6；sys_user.college_id 仅冗余列不参与统计）。</p>
 */
@Mapper
public interface StatsMapper {

    /** 各学院在册教师数 */
    @Select("SELECT p.college_id AS collegeId, COUNT(DISTINCT p.user_id) AS teacherTotal "
            + "FROM user_semester_profile p "
            + "JOIN sys_user_role ur ON ur.user_id = p.user_id AND ur.deleted = 0 "
            + "JOIN sys_role r ON r.id = ur.role_id AND r.deleted = 0 AND r.role_code = 'TEACHER' "
            + "JOIN sys_user u ON u.id = p.user_id AND u.deleted = 0 AND u.status = 1 "
            + "WHERE p.semester_id = #{semesterId} AND p.deleted = 0 "
            + "GROUP BY p.college_id")
    List<Map<String, Object>> countTeachersByCollege(@Param("semesterId") Long semesterId);
}
