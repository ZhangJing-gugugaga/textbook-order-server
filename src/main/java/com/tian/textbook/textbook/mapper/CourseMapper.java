package com.tian.textbook.textbook.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.textbook.entity.Course;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface CourseMapper extends BaseMapper<Course> {

    @Select("SELECT * FROM course WHERE semester_id = #{semesterId} AND code = #{code} AND deleted = 0")
    Course selectBySemesterAndCode(@Param("semesterId") Long semesterId, @Param("code") String code);

    @Select("SELECT * FROM course WHERE semester_id = #{semesterId} AND name = #{name} AND deleted = 0")
    Course selectBySemesterAndName(@Param("semesterId") Long semesterId, @Param("name") String name);

    @Select("SELECT * FROM course WHERE id = #{id} AND deleted = 0")
    Course selectByIdSoft(@Param("id") Long id);

    @Select("SELECT * FROM course WHERE semester_id = #{semesterId} AND deleted = 0")
    List<Course> selectBySemester(@Param("semesterId") Long semesterId);
}
