package com.tian.textbook.textbook.mapper;

import com.tian.textbook.textbook.dto.TeacherCourseListItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 任课关系联表查询（列表回填课程名/教师名/班级名）。
 *
 * <p>独立于既有 TeacherCourseMapper：该接口为已冻结的单表 CRUD 语句（selectByTeacher 等），
 * 联表列表查询在此扩展，避免改动既有 Mapper。SQL 见 TeacherCourseQueryMapper.xml。</p>
 */
@Mapper
public interface TeacherCourseQueryMapper {

    List<TeacherCourseListItem> selectWithNames(@Param("semesterId") Long semesterId,
                                                @Param("teacherId") Long teacherId,
                                                @Param("classId") Long classId);
}
