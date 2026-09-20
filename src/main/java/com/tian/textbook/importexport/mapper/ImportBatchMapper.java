package com.tian.textbook.importexport.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.importexport.entity.ImportBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ImportBatchMapper extends BaseMapper<ImportBatch> {

    @Select("SELECT * FROM import_batch WHERE id = #{id} AND deleted = 0")
    ImportBatch selectByIdSoft(@Param("id") Long id);
}
