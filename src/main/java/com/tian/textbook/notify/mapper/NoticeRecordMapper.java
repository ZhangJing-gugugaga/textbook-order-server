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

    /**
     * 本人在指定任务集合上的确认记录（confirmed_at 非空、deleted=0）。
     * 「我的通知」批量取确认时间，避免逐任务查询（任务数 = 学期任务数）。
     */
    @Select("<script>"
            + "SELECT * FROM notice_record WHERE user_id = #{userId} AND confirmed_at IS NOT NULL "
            + "AND deleted = 0 AND task_id IN "
            + "<foreach collection='taskIds' item='taskId' open='(' separator=',' close=')'>#{taskId}</foreach>"
            + "</script>")
    List<NoticeRecord> selectConfirmedByUserAndTasks(@Param("userId") Long userId,
                                                     @Param("taskIds") java.util.Collection<Long> taskIds);

    @Select("SELECT COALESCE(MAX(round_no), 0) FROM notice_record WHERE task_id = #{taskId} AND deleted = 0")
    Integer selectMaxRoundNo(@Param("taskId") Long taskId);

    @Select("SELECT * FROM notice_record WHERE task_id = #{taskId} AND user_id = #{userId} "
            + "AND round_no = #{roundNo} AND deleted = 0")
    NoticeRecord selectRound(@Param("taskId") Long taskId, @Param("userId") Long userId, @Param("roundNo") int roundNo);

    /**
     * 幂等确认（首次生效）：无确认记录才插入，重复调用不产生第二条确认记录。
     * round_no 为空（契约：确认记录无轮次）。
     */
    @Insert("INSERT INTO notice_record (task_id, user_id, semester_id, round_no, sent_at, send_status, confirmed_at, created_at, updated_at, deleted) "
            + "SELECT #{taskId}, #{userId}, #{semesterId}, NULL, NULL, #{sendStatus}, NOW(3), NOW(3), NOW(3), 0 "
            + "FROM DUAL WHERE NOT EXISTS ("
            + "  SELECT 1 FROM notice_record WHERE task_id = #{taskId} AND user_id = #{userId} "
            + "  AND confirmed_at IS NOT NULL AND deleted = 0)")
    int insertConfirmIfAbsent(@Param("taskId") Long taskId, @Param("userId") Long userId,
                              @Param("semesterId") Long semesterId, @Param("sendStatus") String sendStatus);

    /** 任务最近一轮发送时间（BE-5c：每小时扫描 + interval_hours 间隔判定的依据；无记录返回 null）。 */
    @Select("SELECT MAX(sent_at) FROM notice_record WHERE task_id = #{taskId} "
            + "AND round_no IS NOT NULL AND deleted = 0")
    java.time.LocalDateTime selectLastSentAt(@Param("taskId") Long taskId);

    // ============ 学期归档（BE-5d） ============

    /** 该学期在主表中剩余的通知记录数（归档进度判定）。 */
    @Select("SELECT COUNT(1) FROM notice_record WHERE semester_id = #{semesterId} AND deleted = 0")
    long countBySemester(@Param("semesterId") Long semesterId);

    /**
     * 把该学期的一批通知记录迁入历史表（幂等：{@code uk_history_record(record_id, deleted)} 兜底）。
     *
     * @param limit 单批行数（分批迁移，避免一次搬空大表）
     */
    @Insert("INSERT INTO notice_record_history (record_id, task_id, semester_id, user_id, round_no, sent_at, "
            + "send_status, confirmed_at, archived_at, created_at, updated_at, created_by, updated_by, deleted) "
            + "SELECT id, task_id, semester_id, user_id, round_no, sent_at, send_status, confirmed_at, "
            + "NOW(3), created_at, updated_at, created_by, updated_by, 0 "
            + "FROM notice_record WHERE semester_id = #{semesterId} AND deleted = 0 LIMIT #{limit}")
    int insertHistoryBatch(@Param("semesterId") Long semesterId, @Param("limit") int limit);

    /** 删除已迁入历史表的主表记录（只删历史表里已存在的 record_id，防漏迁）。 */
    @org.apache.ibatis.annotations.Delete("DELETE FROM notice_record WHERE semester_id = #{semesterId} "
            + "AND deleted = 0 AND id IN ("
            + "  SELECT record_id FROM notice_record_history WHERE semester_id = #{semesterId} AND deleted = 0)")
    int deleteArchivedFromMain(@Param("semesterId") Long semesterId);

    /** 归档核对：历史表中该学期的记录数。 */
    @Select("SELECT COUNT(1) FROM notice_record_history WHERE semester_id = #{semesterId} AND deleted = 0")
    long countHistoryBySemester(@Param("semesterId") Long semesterId);

    /** 任务进度：已发送/未授权/失败/已确认人数。详见 resources/mapper/notify/NoticeRecordMapper.xml。 */
    List<java.util.Map<String, Object>> countProgressByTask(@Param("taskId") Long taskId);

    /** 未授权/失败名单（线下兜底，W5/R10）。详见 resources/mapper/notify/NoticeRecordMapper.xml。 */
    List<java.util.Map<String, Object>> selectFailures(@Param("taskId") Long taskId,
                                                       @Param("semesterId") Long semesterId,
                                                       @Param("offset") long offset,
                                                       @Param("limit") long limit);

    long countFailures(@Param("taskId") Long taskId, @Param("semesterId") Long semesterId);

    /**
     * 通知汇总导出行（C4 扩展字段集：学号/工号、姓名、角色、学院、班级、各轮发送时间/状态、
     * 确认状态、确认时间）。详见 resources/mapper/notify/NoticeRecordMapper.xml。
     */
    List<java.util.Map<String, Object>> selectTaskSummaryRows(@Param("taskId") Long taskId,
                                                              @Param("semesterId") Long semesterId);

    /** 与 {@link #selectTaskSummaryRows} 同口径的 COUNT（导出阈值判定用，避免物化全量结果）。 */
    long countTaskSummaryRows(@Param("taskId") Long taskId, @Param("semesterId") Long semesterId);

    /**
     * 看板「未确认通知数」：本学期 active 任务的**未确认目标用户数**（按用户去重）。
     *
     * <p>此前由 {@code NotifyService#countUnconfirmedTargetUsers} 在内存里算——把目标任务角色
     * 的**全部用户**与全部在册 profile 拉进 JVM 做集合差，成本 O(全部用户)（3-4 万行、多轮查询）。
     * 现下沉为单条聚合 SQL：目标用户 = 任务角色 ∩ 该学期在册 ∩ 账号正常，减去已确认者。</p>
     *
     * <p>口径与内存版逐条对齐：{@code uk_task_active} 保证同学期至多 1 个 active 任务，
     * 故「任务 × 角色」的匹配只需按该任务的角色码过滤即可（不存在跨任务串角色的问题）。</p>
     *
     * @param roleCodes 目标角色码（调用方从 active 任务的 target_roles 解析去重）
     */
    @Select("<script>"
            + "SELECT COUNT(DISTINCT u.id) FROM notice_task t "
            + "JOIN sys_role r ON r.deleted = 0 AND r.role_code IN "
            + "<foreach collection='roleCodes' item='code' open='(' separator=',' close=')'>#{code}</foreach> "
            + "JOIN sys_user_role ur ON ur.role_id = r.id AND ur.deleted = 0 "
            + "JOIN sys_user u ON u.id = ur.user_id AND u.deleted = 0 AND u.status = 1 "
            + "JOIN user_semester_profile p ON p.user_id = u.id AND p.semester_id = t.semester_id "
            + "     AND p.deleted = 0 AND p.status = 1 "
            + "WHERE t.semester_id = #{semesterId} AND t.status = 'active' AND t.deleted = 0 "
            + "AND NOT EXISTS (SELECT 1 FROM notice_record nr WHERE nr.task_id = t.id AND nr.user_id = u.id "
            + "     AND nr.confirmed_at IS NOT NULL AND nr.deleted = 0)"
            + "</script>")
    long countUnconfirmedTargetUsers(@Param("semesterId") Long semesterId,
                                     @Param("roleCodes") java.util.Collection<String> roleCodes);
}
