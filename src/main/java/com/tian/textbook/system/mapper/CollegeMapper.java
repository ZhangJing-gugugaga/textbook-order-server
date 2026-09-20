package com.tian.textbook.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.system.entity.College;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CollegeMapper extends BaseMapper<College> {

    @Select("SELECT * FROM college WHERE name = #{name} AND deleted = 0")
    College selectByName(@Param("name") String name);

    @Select("SELECT * FROM college WHERE id = #{id} AND deleted = 0")
    College selectByIdSoft(@Param("id") Long id);
}
