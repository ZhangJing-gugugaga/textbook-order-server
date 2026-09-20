package com.tian.textbook.notify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.notify.entity.NoticeTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface NoticeTaskMapper extends BaseMapper<NoticeTask> {

    @Select("SELECT * FROM notice_task WHERE id = #{id} AND deleted = 0")
    NoticeTask selectByIdSoft(@Param("id") Long id);

    /** 本学期 active 任务（手动任务同学期仅 1 个，W18） */
    @Select("SELECT * FROM notice_task WHERE semester_id = #{semesterId} AND status = 'active' AND deleted = 0")
    List<NoticeTask> selectActiveBySemester(@Param("semesterId") Long semesterId);

    @Select("SELECT * FROM notice_task WHERE semester_id = #{semesterId} AND deleted = 0 ORDER BY id DESC")
    List<NoticeTask> selectBySemester(@Param("semesterId") Long semesterId);
}
