package com.tian.textbook.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.system.entity.Major;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MajorMapper extends BaseMapper<Major> {

    @Select("SELECT * FROM major WHERE college_id = #{collegeId} AND name = #{name} AND deleted = 0")
    Major selectByCollegeAndName(@Param("collegeId") Long collegeId, @Param("name") String name);

    @Select("SELECT * FROM major WHERE id = #{id} AND deleted = 0")
    Major selectByIdSoft(@Param("id") Long id);
}
