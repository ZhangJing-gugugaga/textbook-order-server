package com.tian.textbook.importexport.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.importexport.entity.ExportTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExportTaskMapper extends BaseMapper<ExportTask> {

    /** MP 自动 resultMap（含 params_json 的 JacksonTypeHandler）。 */
    String RESULT_MAP = "com.tian.textbook.importexport.mapper.ExportTaskMapper.mybatis-plus_ExportTask";

    @ResultMap(RESULT_MAP)
    @Select("SELECT * FROM export_task WHERE id = #{id} AND deleted = 0")
    ExportTask selectByIdSoft(@Param("id") Long id);

    @ResultMap(RESULT_MAP)
    @Select("SELECT * FROM export_task WHERE download_token = #{token} AND deleted = 0")
    ExportTask selectByToken(@Param("token") String token);

    /** 过期文件清理：status='done' 且 expires_at 已过 */
    @ResultMap(RESULT_MAP)
    @Select("SELECT * FROM export_task WHERE status = 'done' AND expires_at IS NOT NULL "
            + "AND expires_at &lt; #{before} AND deleted = 0")
    List<ExportTask> selectExpired(@Param("before") java.time.LocalDateTime before);
}
