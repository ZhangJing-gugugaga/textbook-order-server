package com.tian.textbook.textbook.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.textbook.entity.TeacherCourse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface TeacherCourseMapper extends BaseMapper<TeacherCourse> {

    @Select("SELECT * FROM teacher_course WHERE semester_id = #{semesterId} AND teacher_id = #{teacherId} AND deleted = 0")
    List<TeacherCourse> selectByTeacher(@Param("semesterId") Long semesterId, @Param("teacherId") Long teacherId);

    @Select("SELECT * FROM teacher_course WHERE semester_id = #{semesterId} AND class_id = #{classId} AND deleted = 0")
    List<TeacherCourse> selectByClass(@Param("semesterId") Long semesterId, @Param("classId") Long classId);

    @Select("SELECT * FROM teacher_course WHERE semester_id = #{semesterId} AND teacher_id = #{teacherId} "
            + "AND course_id = #{courseId} AND class_id = #{classId} AND deleted = 0")
    TeacherCourse selectExact(@Param("semesterId") Long semesterId, @Param("teacherId") Long teacherId,
                              @Param("courseId") Long courseId, @Param("classId") Long classId);
}
