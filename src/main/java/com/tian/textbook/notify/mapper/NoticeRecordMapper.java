package com.tian.textbook.notify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tian.textbook.notify.entity.NoticeRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface NoticeRecordMapper extends BaseMapper<NoticeRecord> {

    @Select("SELECT * FROM notice_record WHERE task_id = #{taskId} AND user_id = #{userId} "
            + "AND confirmed_at IS NOT NULL AND deleted = 0")
    NoticeRecord selectConfirmed(@Param("taskId") Long taskId, @Param("userId") Long userId);

    @Select("SELECT * FROM notice_record WHERE task_id = #{taskId} AND user_id = #{userId} AND deleted = 0 ORDER BY round_no")
    List<NoticeRecord> selectByTaskAndUser(@Param("taskId") Long taskId, @Param("userId") Long userId);

    @Select("SELECT COALESCE(MAX(round_no), 0) FROM notice_record WHERE task_id = #{taskId} AND deleted = 0")
    Integer selectMaxRoundNo(@Param("taskId") Long taskId);

    @Select("SELECT * FROM notice_record WHERE task_id = #{taskId} AND user_id = #{userId} "
            + "AND round_no = #{roundNo} AND deleted = 0")
    NoticeRecord selectRound(@Param("taskId") Long taskId, @Param("userId") Long userId, @Param("roundNo") int roundNo);

    /**
     * 幂等确认（首次生效）：无确认记录才插入，重复调用不产生第二条确认记录。
     * round_no 为空（契约：确认记录无轮次）。
     */
    @Insert("INSERT INTO notice_record (task_id, user_id, round_no, sent_at, send_status, confirmed_at, created_at, updated_at, deleted) "
            + "SELECT #{taskId}, #{userId}, NULL, NULL, 'confirmed', NOW(3), NOW(3), NOW(3), 0 "
            + "FROM DUAL WHERE NOT EXISTS ("
            + "  SELECT 1 FROM notice_record WHERE task_id = #{taskId} AND user_id = #{userId} "
            + "  AND confirmed_at IS NOT NULL AND deleted = 0)")
    int insertConfirmIfAbsent(@Param("taskId") Long taskId, @Param("userId") Long userId);

    /** 任务进度：已发送/未授权/失败/已确认人数 */
    List<java.util.Map<String, Object>> countProgressByTask(@Param("taskId") Long taskId);
}
