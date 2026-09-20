package com.tian.textbook.semester.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.semester.entity.UserSemesterProfile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserSemesterProfileMapper extends BaseMapper<UserSemesterProfile> {

    @Select("SELECT * FROM user_semester_profile WHERE user_id = #{userId} AND semester_id = #{semesterId} AND deleted = 0")
    UserSemesterProfile selectByUserAndSemester(@Param("userId") Long userId, @Param("semesterId") Long semesterId);

    @Select("SELECT college_id FROM user_semester_profile WHERE user_id = #{userId} AND semester_id = #{semesterId} AND deleted = 0")
    Long selectCollegeId(@Param("userId") Long userId, @Param("semesterId") Long semesterId);

    @Select("SELECT * FROM user_semester_profile WHERE semester_id = #{semesterId} AND deleted = 0")
    List<UserSemesterProfile> selectBySemester(@Param("semesterId") Long semesterId);
}
