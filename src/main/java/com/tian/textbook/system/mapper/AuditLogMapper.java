package com.tian.textbook.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.system.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {

    /**
     * MP 自动 resultMap（@TableName(autoResultMap=true) 生成，含 detail_json 的
     * JacksonTypeHandler）：自定义 @Select 不会自动套用，需显式引用，否则 JSON 列被
     * 自动映射静默丢弃（审计详情读回为 null）。XML 的 selectByResource 同样显式引用该 id。
     */
    String RESULT_MAP = "com.tian.textbook.system.mapper.AuditLogMapper.mybatis-plus_AuditLog";

    /**
     * 审计查询（W24：按操作者/动作/时间过滤），DB 侧分页。
     *
     * <p>审计表只增不减，此前一次性取出全部匹配行、再在 Java 里 subList，数据量上去后
     * 单次查询即内存尖峰。现由 SQL 的 LIMIT/OFFSET 承担分页。</p>
     */
    @ResultMap(RESULT_MAP)
    @Select("<script>"
            + "SELECT * FROM audit_log WHERE 1=1 "
            + "<if test='userId != null'> AND user_id = #{userId}</if>"
            + "<if test='userNo != null and userNo != \"\"'> AND user_no LIKE CONCAT(#{userNo}, '%')</if>"
            + "<if test='action != null and action != \"\"'> AND action = #{action}</if>"
            + "<if test='resource != null and resource != \"\"'> AND resource = #{resource}</if>"
            + "<if test='startAt != null'> AND at &gt;= #{startAt}</if>"
            + "<if test='endAt != null'> AND at &lt;= #{endAt}</if>"
            + " ORDER BY at DESC, id DESC"
            + " LIMIT #{limit} OFFSET #{offset}"
            + "</script>")
    List<AuditLog> selectByFilter(@Param("userId") Long userId,
                                  @Param("userNo") String userNo,
                                  @Param("action") String action,
                                  @Param("resource") String resource,
                                  @Param("startAt") LocalDateTime startAt,
                                  @Param("endAt") LocalDateTime endAt,
                                  @Param("offset") long offset,
                                  @Param("limit") long limit);

    /** 与 {@link #selectByFilter} 同条件的总数（分页 total）。 */
    @Select("<script>"
            + "SELECT COUNT(1) FROM audit_log WHERE 1=1 "
            + "<if test='userId != null'> AND user_id = #{userId}</if>"
            + "<if test='userNo != null and userNo != \"\"'> AND user_no LIKE CONCAT(#{userNo}, '%')</if>"
            + "<if test='action != null and action != \"\"'> AND action = #{action}</if>"
            + "<if test='resource != null and resource != \"\"'> AND resource = #{resource}</if>"
            + "<if test='startAt != null'> AND at &gt;= #{startAt}</if>"
            + "<if test='endAt != null'> AND at &lt;= #{endAt}</if>"
            + "</script>")
    long countByFilter(@Param("userId") Long userId,
                       @Param("userNo") String userNo,
                       @Param("action") String action,
                       @Param("resource") String resource,
                       @Param("startAt") LocalDateTime startAt,
                       @Param("endAt") LocalDateTime endAt);

    /** 窗口变更记录查询（谁/何时/原值→新值，W24）。详见 resources/mapper/system/AuditLogMapper.xml。 */
    List<AuditLog> selectByResource(@Param("resource") String resource,
                                    @Param("resourceId") String resourceId,
                                    @Param("offset") long offset,
                                    @Param("limit") long limit);

    long countByResource(@Param("resource") String resource, @Param("resourceId") String resourceId);
}
