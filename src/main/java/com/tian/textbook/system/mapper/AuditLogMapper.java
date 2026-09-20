package com.tian.textbook.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.system.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    /**
     * 审计查询（W24：按操作者/动作/时间过滤 + 分页由 Page 参数在外层处理）。
     * 明细见 resources/mapper/system/AuditLogMapper.xml。
     */
    @Select("<script>"
            + "SELECT * FROM audit_log WHERE 1=1 "
            + "<if test='userId != null'> AND user_id = #{userId}</if>"
            + "<if test='userNo != null and userNo != \"\"'> AND user_no LIKE CONCAT(#{userNo}, '%')</if>"
            + "<if test='action != null and action != \"\"'> AND action = #{action}</if>"
            + "<if test='resource != null and resource != \"\"'> AND resource = #{resource}</if>"
            + "<if test='startAt != null'> AND at &gt;= #{startAt}</if>"
            + "<if test='endAt != null'> AND at &lt;= #{endAt}</if>"
            + " ORDER BY at DESC"
            + "</script>")
    List<AuditLog> selectByFilter(@Param("userId") Long userId,
                                  @Param("userNo") String userNo,
                                  @Param("action") String action,
                                  @Param("resource") String resource,
                                  @Param("startAt") LocalDateTime startAt,
                                  @Param("endAt") LocalDateTime endAt);
}
