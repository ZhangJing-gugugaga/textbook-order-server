package com.tian.textbook.semester.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.semester.entity.Semester;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SemesterMapper extends BaseMapper<Semester> {

    @Select("SELECT * FROM semester WHERE active_status = 'active' AND deleted = 0")
    Semester selectActive();

    @Select("SELECT * FROM semester WHERE id = #{id} AND deleted = 0")
    Semester selectByIdSoft(@Param("id") Long id);

    /** 原子切换：仅当仍为 draft 且 version 匹配才置 active（乐观锁，W1） */
    @Update("UPDATE semester SET active_status = 'active', updated_at = NOW(3) "
            + "WHERE id = #{id} AND active_status = 'draft' AND version = #{version}")
    int activateIfDraft(@Param("id") Long id, @Param("version") int version);

    /** 归档当前 active 学期（切换事务第二步） */
    @Update("UPDATE semester SET active_status = 'archived', updated_at = NOW(3) "
            + "WHERE id = #{id} AND active_status = 'active'")
    int archiveIfActive(@Param("id") Long id);
}
