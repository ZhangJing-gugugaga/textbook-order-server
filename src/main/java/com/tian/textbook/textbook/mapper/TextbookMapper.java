package com.tian.textbook.textbook.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.textbook.entity.Textbook;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TextbookMapper extends BaseMapper<Textbook> {

    @Select("SELECT * FROM textbook WHERE isbn = #{isbn} AND deleted = 0")
    Textbook selectByIsbn(@Param("isbn") String isbn);

    @Select("SELECT * FROM textbook WHERE id = #{id} AND deleted = 0")
    Textbook selectByIdSoft(@Param("id") Long id);
}
