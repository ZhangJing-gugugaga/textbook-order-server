package com.tian.textbook.importexport.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.importexport.entity.ImportBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ImportBatchMapper extends BaseMapper<ImportBatch> {

    /** MP 自动 resultMap（含 error_detail 的 JacksonTypeHandler）。 */
    String RESULT_MAP = "com.tian.textbook.importexport.mapper.ImportBatchMapper.mybatis-plus_ImportBatch";

    @ResultMap(RESULT_MAP)
    @Select("SELECT * FROM import_batch WHERE id = #{id} AND deleted = 0")
    ImportBatch selectByIdSoft(@Param("id") Long id);
}
