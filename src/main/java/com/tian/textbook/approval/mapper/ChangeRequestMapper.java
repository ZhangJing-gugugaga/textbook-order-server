package com.tian.textbook.approval.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.approval.entity.ChangeRequest;
import com.tian.textbook.common.annotation.CollegeScope;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ChangeRequestMapper extends BaseMapper<ChangeRequest> {

    /** MP 自动 resultMap（含 payload_json / field_check_result 的 JacksonTypeHandler）。 */
    String RESULT_MAP = "com.tian.textbook.approval.mapper.ChangeRequestMapper.mybatis-plus_ChangeRequest";

    @ResultMap(RESULT_MAP)
    @Select("SELECT * FROM change_request WHERE id = #{id} AND deleted = 0")
    ChangeRequest selectByIdSoft(@Param("id") Long id);

    @ResultMap(RESULT_MAP)
    @Select("SELECT * FROM change_request WHERE batch_no = #{batchNo} AND deleted = 0 ORDER BY id")
    List<ChangeRequest> selectByBatchNo(@Param("batchNo") String batchNo);

    /** 我的提交记录（数据隔离：applicant_id = 本人） */
    @CollegeScope(userColumn = "applicant_id")
    @ResultMap(RESULT_MAP)
    @Select("SELECT * FROM change_request WHERE applicant_id = #{applicantId} AND deleted = 0 ORDER BY id DESC")
    List<ChangeRequest> selectMyRequests(@Param("applicantId") Long applicantId);

    /** 审批列表分页（详见 resources/mapper/approval/ChangeRequestMapper.xml） */
    List<com.tian.textbook.approval.dto.ChangeRequestListItem> selectPageByFilter(
            @Param("semesterId") Long semesterId,
            @Param("status") String status,
            @Param("batchNo") String batchNo,
            @Param("type") String type,
            @Param("offset") long offset,
            @Param("limit") long limit);

    long countByFilter(@Param("semesterId") Long semesterId,
                       @Param("status") String status,
                       @Param("batchNo") String batchNo,
                       @Param("type") String type);
}
