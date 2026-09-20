package com.tian.textbook.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.system.entity.SchoolClass;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SchoolClassMapper extends BaseMapper<SchoolClass> {

    @Select("SELECT * FROM school_class WHERE major_id = #{majorId} AND name = #{name} AND deleted = 0")
    SchoolClass selectByMajorAndName(@Param("majorId") Long majorId, @Param("name") String name);

    @Select("SELECT * FROM school_class WHERE id = #{id} AND deleted = 0")
    SchoolClass selectByIdSoft(@Param("id") Long id);
}
